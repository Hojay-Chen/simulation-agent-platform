package com.luxera.companion.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.luxera.companion.common.BusinessException;
import com.luxera.companion.contracts.application.ActionRequest;
import com.luxera.companion.contracts.application.ActionResponse;
import com.luxera.companion.contracts.application.ActionStatus;
import com.luxera.companion.contracts.application.InvocationContext;
import com.luxera.companion.contracts.application.ResourceView;
import com.luxera.companion.contracts.spi.ApplicationRuntimePort;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 提醒 —— <b>数字人侧通往 {@code com.luxera.reminder} 的唯一一道门</b>。
 *
 * <p>LAP v1 R5 之前, 提醒是数字人自己的一张表: {@code ReminderRepository} 直接
 * {@code save()} 进 {@code reminders}, {@code ProactiveEngine} 到点扫它、转成通知、
 * 再把它标成 done。于是"提醒"这件事有两份真相 —— 数字人这张表, 和用户装的那个提醒应用。
 * 两份真相不会自己保持一致, 只会各自漂移。
 *
 * <p>现在这里一行 SQL 也没有, 一行 JPA 也没有。四个方法加起来只做三件事:
 * <b>读 {@code reminder://owner/{userId}}</b>、<b>写 {@code reminder.create/complete/cancel}</b>、
 * 以及在两者之间翻译字段名。提醒的存储、去重规则(生日一年一条)、到点扫描, 全在应用里。
 *
 * <h2>为什么要 {@code ensureSession}</h2>
 * <p>提醒是数字人的一个功能, 但它落在用户<b>打开的那个会话</b>里。数字人不在启动时替所有人批量
 * 开会话 —— 那是用户的选择; 也不该由部署脚本往库里塞行。改成"用的时候保证一下": 第一次用到时
 * 开一个并把这个人记为 OWNER, 之后每次都是无操作({@code ensureSession} 天生幂等)。
 *
 * <p>LAP v2 之前这里叫 {@code ensureInstalled}, 保证的是"这个人装过提醒应用"。安装没有了之后,
 * 同一个位置上要保证的东西变成了"这个人在提醒应用里有一个正在进行的实例" —— 那正是 Session。
 * 调用点只有这一处, 改的也只有这一行。
 *
 * <h2>为什么每次都用一个全新的 correlationId</h2>
 * <p>进程内调用的幂等键是从 {@code correlationId + target} 派生的, 而 {@code target} 对提醒
 * 来说永远是<em>同一个收件箱</em>。若共用一个 correlationId, 在同一个收件箱里连建两条提醒
 * 就会撞成同一个键, 第二条被当成第一条的重放 —— 用户说"提醒我喝水"说了两遍, 只会得到一条。
 * 每次调用都是一次新的意图, 幂等键必须跟着意图走。真正需要去重的地方(生日)由<em>应用</em>
 * 判定, 那是业务规则, 该长在数据旁边。
 *
 * <h2>失败为什么抛异常</h2>
 * <p>这个类的调用方是数字人自己的认知链(对话里解析出的提醒、每日生日检查)。失败要让它们
 * 知道, 否则"提醒功能不工作"会变成一个必须翻日志才能定位的现象。降级由调用方决定:
 * {@link ReminderPlanner} 选择不打断对话, {@link BirthdayService} 选择跳过这一个伴侣。
 */
@Slf4j
@Service
public class ReminderService {

    /** 提醒应用的稳定 id —— 与它的 manifest {@code identity.id} 一字不差。 */
    public static final String APP_ID = "com.luxera.reminder";

    /** 收件箱 URI 模板 —— 与 manifest 的 {@code resources[].uriTemplate} 一致。 */
    static final String INBOX_PREFIX = "reminder://owner/";

    private static final String ACTION_CREATE = "reminder.create";
    private static final String ACTION_COMPLETE = "reminder.complete";
    private static final String ACTION_CANCEL = "reminder.cancel";

    private final ApplicationRuntimePort port;
    private final ObjectMapper objectMapper;

    public ReminderService(ApplicationRuntimePort port, ObjectMapper objectMapper) {
        this.port = port;
        this.objectMapper = objectMapper;
    }

    /** 某个人的收件箱 —— 提醒属于人, 不属于会话, 所以 URI 里没有会话段。 */
    static String inboxOf(String userId) {
        return INBOX_PREFIX + userId;
    }

    // ─────────────────────────── 读 ───────────────────────────

    /** 这个人的提醒, 按时间先后; 给出 {@code companionId} 时只看与他相关的那几条。 */
    public List<Reminder> list(String userId, String companionId) {
        List<Reminder> all = inbox(userId);
        if (!StringUtils.hasText(companionId)) {
            return all;
        }
        List<Reminder> mine = new ArrayList<>();
        for (Reminder r : all) {
            if (companionId.equals(r.getCompanionId())) {
                mine.add(r);
            }
        }
        return mine;
    }

    private List<Reminder> inbox(String userId) {
        ResourceView view = port.read(inboxOf(userId)).orElse(null);
        if (view == null || view.state() == null) {
            return List.of();
        }
        JsonNode items = view.state().path("items");
        if (!items.isArray()) {
            return List.of();
        }
        List<Reminder> out = new ArrayList<>(items.size());
        for (JsonNode item : items) {
            out.add(toReminder(userId, item));
        }
        return out;
    }

    // ─────────────────────────── 写 ───────────────────────────

    public Reminder create(String userId, String companionId, String type, String title,
                           String content, LocalDateTime remindAt) {
        if (remindAt == null) {
            throw BusinessException.badRequest("提醒时间不能为空");
        }
        ObjectNode input = objectMapper.createObjectNode();
        input.put("title", title);
        // 到这里为止 remindAt 都是 LocalDateTime, 应用那边期望的是 2026-09-12T15:00 这样的字符串。
        // LocalDateTime.toString() 恰好是同一规则(秒为 0 时省略秒) —— 与应用对外的承诺一致。
        input.put("dueAt", remindAt.toString());
        if (StringUtils.hasText(type)) {
            input.put("type", type);
        }
        if (StringUtils.hasText(content)) {
            input.put("note", content);
        }
        if (StringUtils.hasText(companionId)) {
            input.put("companionId", companionId);
        }
        return fromResponse(userId, call(userId, ACTION_CREATE, input, "创建提醒"));
    }

    public Reminder markDone(String userId, String reminderId) {
        ObjectNode input = objectMapper.createObjectNode().put("reminderId", reminderId);
        return fromResponse(userId, call(userId, ACTION_COMPLETE, input, "完成提醒"));
    }

    public void delete(String userId, String reminderId) {
        ObjectNode input = objectMapper.createObjectNode().put("reminderId", reminderId);
        call(userId, ACTION_CANCEL, input, "取消提醒");
    }

    // ─────────────────────────── 通往应用的窄门 ───────────────────────────

    /**
     * 一次进程内调用。身份是<b>显式写下的 {@code HUMAN(userId)}</b> —— 提醒的所有者是这个人,
     * 应用会核对 URI 里的 {@code ownerId} 与 principal 是不是同一个, 所以这里不能拿数字人自己
     * 的 companionId 去调(那会落进"别人的收件箱"而被拒)。
     *
     * <p>这正是 {@code InternalPrincipalResolver} 那句注释的意思: 显式写了 HUMAN 是合法的
     * (数字人代理真人的意图), 但必须是写的, 不是猜的。
     */
    private ActionResponse call(String userId, String action, ObjectNode input, String what) {
        InvocationContext ctx = InvocationContext.human(userId, UUID.randomUUID().toString());
        ensureSession(ctx, what);
        // 刻意<em>不</em>把会话 id 塞进 ctx: 收件箱是"属于人"的资源, 一旦把某个会话写进它的
        // resource 行, 那个会话结束时这一行就指向一个已经没了的会话 —— 而收件箱一天之内可能要
        // 活过好几个会话。网关的会话解析第 4 档("这个人最近的 ACTIVE 会话")给出的正是刚刚
        // 保证过的那个, 而且它不往资源行上写任何东西。
        ActionResponse response = port.execute(ActionRequest.of(action, inboxOf(userId), input), ctx);
        if (!response.isSuccess()) {
            throw failure(what, response);
        }
        return response;
    }

    /**
     * 保证这个人在提醒应用里有一个会话。<b>只为了副作用</b> —— 这里要的不是那个 id(见
     * {@link #call} 里的注释), 而是"接下来那个动作一定有一个会话可落"。
     *
     * <p>建不出来就让调用方知道, 不静默降级: "提醒不工作"必须是能看见的, 而不是翻日志才能定位的。
     */
    private void ensureSession(InvocationContext ctx, String what) {
        try {
            port.ensureSession(APP_ID, ctx);
        } catch (Exception e) {
            log.warn("[提醒] 开启 {} 的会话失败: {}", APP_ID, e.getMessage());
            throw new BusinessException(HttpStatus.SERVICE_UNAVAILABLE,
                    what + "失败: 提醒应用暂不可用", "REMINDER_APP_UNAVAILABLE");
        }
    }

    private static BusinessException failure(String what, ActionResponse response) {
        String code = response.error() == null ? null : response.error().code();
        String message = response.error() == null ? null : response.error().message();
        return new BusinessException(httpOf(response.status()),
                what + "失败: " + (StringUtils.hasText(message) ? message : response.status().name()),
                code);
    }

    /** 与平台 {@code ActionStatusMapper} 同一套语义 —— 这里只需要它, 不需要那个类(它在别的模块)。 */
    private static HttpStatus httpOf(ActionStatus status) {
        return switch (status) {
            case INVALID_ARGUMENT, IDEMPOTENCY_KEY_REQUIRED, IDEMPOTENCY_KEY_REUSED -> HttpStatus.BAD_REQUEST;
            case DENIED, REQUIRE_CONFIRMATION -> HttpStatus.FORBIDDEN;
            case NOT_FOUND -> HttpStatus.NOT_FOUND;
            case STATE_CONFLICT, IDEMPOTENCY_IN_PROGRESS -> HttpStatus.CONFLICT;
            default -> HttpStatus.INTERNAL_SERVER_ERROR;
        };
    }

    // ─────────────────────────── 翻译 ───────────────────────────

    /**
     * 应用的 {@code state.items[]} → 数字人的 {@link Reminder} —— <b>整个数字人里唯一认识
     * 提醒字段名的地方</b>。别的类只看见 {@link Reminder} 这个形状。
     */
    private Reminder toReminder(String userId, JsonNode item) {
        Reminder r = new Reminder();
        r.setId(text(item, "id"));
        r.setUserId(userId);
        r.setCompanionId(text(item, "companionId"));
        r.setType(text(item, "type"));
        r.setTitle(text(item, "title"));
        r.setContent(text(item, "note"));
        r.setRemindAt(time(text(item, "dueAt")));
        r.setStatus(dhStatus(text(item, "status")));
        r.setCreatedAt(time(text(item, "createdAt")));
        return r;
    }

    /**
     * 动作的响应 → {@link Reminder}。
     *
     * <p>先看 {@code response.resource()} 里那一条 —— 动作的 {@code result} 只有
     * {@code {id, type, title, dueAt, status}} 五个字段(它是给调用方看"办成了什么"的),
     * 而资源里是完整的那条提醒。拿不到资源行时才退回 result, 宁可字段少一点也不要空手而归。
     */
    private Reminder fromResponse(String userId, ActionResponse response) {
        String id = text(response.result(), "id");
        ResourceView resource = response.resource();
        if (id != null && resource != null && resource.state() != null) {
            for (JsonNode item : resource.state().path("items")) {
                if (id.equals(text(item, "id"))) {
                    return toReminder(userId, item);
                }
            }
        }
        return toReminder(userId, response.result());
    }

    /**
     * 应用的词汇 → 数字人的词汇。
     *
     * <p>{@code DISPATCHED} 折算成 {@code done}: 它说的是"这条提醒已经响过了"。在旧的实现里,
     * 响过之后 {@code ProactiveEngine} 正是把它标成 {@code done} 的 —— 前端那一行
     * {@code r.status === 'done' ? 'line-through' : ...} 因此还是老样子。
     */
    static String dhStatus(String appStatus) {
        if (appStatus == null) {
            return Reminder.STATUS_PENDING;
        }
        return switch (appStatus) {
            case "PENDING" -> Reminder.STATUS_PENDING;
            case "DISPATCHED", "DONE" -> Reminder.STATUS_DONE;
            case "CANCELLED" -> Reminder.STATUS_CANCELLED;
            default -> appStatus.toLowerCase();
        };
    }

    private static String text(JsonNode node, String field) {
        if (node == null || !node.isObject()) {
            return null;
        }
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }

    private static LocalDateTime time(String text) {
        if (!StringUtils.hasText(text)) {
            return null;
        }
        try {
            return LocalDateTime.parse(text.trim());
        } catch (Exception e) {
            return null;
        }
    }
}
