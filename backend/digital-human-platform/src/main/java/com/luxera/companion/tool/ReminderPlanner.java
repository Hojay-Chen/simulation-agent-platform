package com.luxera.companion.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.luxera.companion.llm.LlmRouter;
import com.luxera.companion.llm.StructuredRequest;
import com.luxera.companion.runtime.AgentApplicationFlow;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;

/**
 * 聊天内建提醒(设计文档 56-58 节): 用户说"帮我记得…"时,
 * 用 LLM 解析提醒内容与时间并创建 Reminder,返回一句给 Prompt 的确认上下文。
 *
 * <p>LAP v1: 它创建提醒的方式与真人在前端点"添加"走的是<b>同一条路</b>({@link ReminderService}
 * → {@code reminder.create})。这不是为了好看 —— 它是"数字人的认知也只是一个调用方"这件事的
 * 可执行版本: 应用不需要知道这次调用来自对话还是来自一个按钮, 权限、幂等、归属校验因此只有
 * 一套。
 *
 * <p>LAP v1 R7: 它不再假定"用户想请求工具 = 用户想要提醒"。第一步先问平台的
 * {@link AgentApplicationFlow#route} —— 意图 → 能力 → 应用 —— 只有路由结果落在提醒这个能力
 * 上才继续。多出来的这一次 LLM 调用是<b>这一层唯一该花的地方</b>: 它换来的是"数字人认识的是
 * 能力目录, 不是某一个应用", 也就是再加第二个提醒类应用时, 认识的还是那份目录。
 */
@Slf4j
@Component
public class ReminderPlanner {

    /** 提醒应用声明在 manifest 里的能力 —— 路由结果必须落在它上面。 */
    private static final String CAPABILITY = "reminder.manage";

    private static final String SYSTEM_TEMPLATE = """
            你是提醒解析器。判断用户是否想让伴侣帮忙提醒/记住某件事。
            今天是 %s,现在是 %s。请基于这个"今天"计算 remind_at。
            输出严格 JSON,不要输出其他内容:
            {
              "remind": true,
              "title": "提醒事项标题(简洁)",
              "content": "补充说明,可为空",
              "remind_at": "yyyy-MM-ddTHH:mm 或空字符串(未说时间)",
              "type": "user_set"
            }
            规则:
            - 用户明确说"提醒/记得/帮我记/别忘了"之类 → remind=true
            - 没提时间 → remind_at 为空字符串
            - 不是提醒请求 → remind=false,其余字段空
            """;

    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("M月d日 HH:mm");

    private final LlmRouter llm;
    private final ReminderService reminderService;
    private final AgentApplicationFlow applicationFlow;

    public ReminderPlanner(LlmRouter llm, ReminderService reminderService,
                           AgentApplicationFlow applicationFlow) {
        this.llm = llm;
        this.reminderService = reminderService;
        this.applicationFlow = applicationFlow;
    }

    /** 尝试从用户消息创建提醒;成功返回供 Prompt 注入的确认上下文,否则 null */
    public String tryCreateFromMessage(String userId, String companionId, String userText) {
        if (!StringUtils.hasText(userText)) return null;
        try {
            // 第 0 步: 平台说这件事该用哪个应用? 它只说得出能力目录里的东西。
            var intent = applicationFlow.route(companionId, userText);
            if (intent.isEmpty() || !CAPABILITY.equals(intent.get().capabilityId())) {
                return null;
            }
            // 路由到了提醒能力, 但那个应用得是我会驱动的那一个 —— 换一个提醒应用,
            // 它的 action id、资源 URI、字段名都不同, 拿着旧词汇去调只会撞墙。
            if (!ReminderService.APP_ID.equals(intent.get().applicationId())) {
                log.info("[提醒] 平台把 {} 路由到了 {}, 那不是本适配器认识的应用, 放弃",
                        CAPABILITY, intent.get().applicationId());
                return null;
            }

            String sys = String.format(SYSTEM_TEMPLATE, LocalDate.now(), LocalTime.now().withNano(0).withSecond(0));
            var res = llm.structured(StructuredRequest.builder()
                    .task("reminder-extraction")
                    .system(sys)
                    .user(userText)
                    .temperature(0.2)
                    .build());
            JsonNode root = res.getJson();
            if (!root.path("remind").asBoolean(false)) return null;
            String title = root.path("title").asText("");
            if (!StringUtils.hasText(title)) return null;

            LocalDateTime remindAt = parseTime(root.path("remind_at").asText(""));
            if (remindAt == null) remindAt = LocalDateTime.now().plusHours(1);

            Reminder r = reminderService.create(userId, companionId, "user_set", title,
                    root.path("content").asText(null), remindAt);
            return "你刚为用户创建了提醒:「" + r.getTitle() + "」,时间 " + FMT.format(r.getRemindAt())
                    + "。请在回复里自然地确认你已经记住了这件事。";
        } catch (Exception e) {
            log.debug("提醒解析失败: {}", e.getMessage());
            return null;
        }
    }

    private static LocalDateTime parseTime(String s) {
        if (!StringUtils.hasText(s)) return null;
        s = s.trim();
        LocalDateTime parsed = null;
        try {
            parsed = LocalDateTime.parse(s);   // ISO yyyy-MM-ddTHH:mm
        } catch (Exception ignored) {
        }
        if (parsed == null) {
            try {
                parsed = LocalDateTime.parse(s.replace(' ', 'T'));
            } catch (Exception ignored) {
            }
        }
        if (parsed == null) {
            try {
                parsed = LocalDate.now().atTime(LocalTime.parse(s));   // HH:mm
            } catch (Exception ignored) {
                return null;
            }
        }
        // 兜底: 解析出的时间在过去(如模型幻觉了错误年份) → 交给调用方用默认 +1h
        if (parsed.isBefore(LocalDateTime.now())) return null;
        return parsed;
    }
}
