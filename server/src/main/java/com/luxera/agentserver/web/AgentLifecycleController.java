package com.luxera.agentserver.web;

import com.luxera.companion.config.CurrentUser;
import com.luxera.companion.persona.AgentLifecycle;
import com.luxera.companion.persona.AgentSwitchService;
import com.luxera.companion.persona.Companion;
import com.luxera.companion.persona.CompanionService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Agent 开关的**控制台面** —— Being Studio 上那个"停 / 继续"按钮背后的东西。
 *
 * <h2>它在哪一面上, 以及为什么</h2>
 *
 * 落在 {@code /api/**}, 由 {@code ServerSecurityConfig} 的
 * {@code anyRequest().authenticated()} 要求用户 JWT —— 与其余内省接口同门。
 * 这一面回答的是"**我**的 agent 现在是什么状态", 于是它必须知道"我是谁":
 * 单agent 的操作走 {@code CompanionService#requireOwned}, 批量操作**只作用于调用者
 * 自己的 agent**。
 *
 * <p>平台级的"全部关掉"因此**不在这里** —— 它是运维动作, 不是某个用户对自己 agent
 * 的操作, 放在一个用户 JWT 面上会让任何登录用户都能让别人的 agent 停工。它在
 * 8092 的管理密钥面(X-Admin-Key), 见 {@code OpenApiAdminLifecycleController}。
 * 两个面各回答一个问题, 这是这个平台既有的分层方式(见 {@code faceOf()} 的四张面),
 * 不是为这个功能临时发明的。
 *
 * <h2>为什么是 PUT 一个状态, 而不是 POST 两个动词</h2>
 *
 * 暂停与恢复是**同一根列的两个取值**, 不是一个对象上的两个动作。写成
 * {@code PUT .../lifecycle} + {@code {"lifecycle":"paused"}} 之后:
 *
 * <ul>
 *   <li>重复提交天然幂等 —— 而 {@code POST /pause} 需要调用方去猜"重复调用算不算错"。</li>
 *   <li>将来若真的出现第三档(方案里提过 sleeping), 加一个枚举值就够了, 不必再加一个动词
 *       和一条路由。</li>
 *   <li>控制台的开关是一个受控组件: 它渲染"当前值", 用户拨动它, 它提交"新值"。
 *       这正是 PUT 的形状。</li>
 * </ul>
 *
 * <h2>路由为什么不会打架</h2>
 *
 * 三条路由的前缀段数不同, 且**没有一条对 {@code /api/agents/{id}/lifecycle} 定义 POST**
 * —— 批量的两条(三个字面段)因此在结构上不可能被 {@code {id}} 吃掉。这条性质由
 * {@code AgentLifecycleRoutingTest} 钉住, 不靠人记。
 */
@Slf4j
@RestController
@RequestMapping("/api/agents")
public class AgentLifecycleController {

    private final AgentSwitchService agentSwitch;
    private final CompanionService companionService;
    private final CurrentUser currentUser;

    public AgentLifecycleController(AgentSwitchService agentSwitch,
                                    CompanionService companionService,
                                    CurrentUser currentUser) {
        this.agentSwitch = agentSwitch;
        this.companionService = companionService;
        this.currentUser = currentUser;
    }

    /**
     * 开关的全景: 部署级总闸 + 各 agent 的状态 + 被拦下的调用计数。
     *
     * <p>控制台一次取全, 不写 1+N 次请求 —— 与 {@code LapCatalogController} 同一个理由。
     * 这里只有十几到几十行, 而且它要的是一张能一眼看完的表。
     */
    @GetMapping("/lifecycle")
    public Map<String, Object> overview() {
        String userId = currentUser.requireUserId();
        List<Map<String, Object>> agents = new ArrayList<>();
        for (Companion c : companionService.list(userId)) {
            agents.add(row(c));
        }
        AgentSwitchService.Stats stats = agentSwitch.stats();

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("agents", agents);
        out.put("mineActive", agents.stream().filter(a -> !Boolean.TRUE.equals(a.get("paused"))).count());
        out.put("minePaused", agents.stream().filter(a -> Boolean.TRUE.equals(a.get("paused"))).count());
        // 全平台的口径也一并给出: 它回答"我关了的是不是全部" —— 一个只看自己那份列表
        // 的界面无法回答这个问题, 而运维按下"全部关掉"之后想确认的正是它
        out.put("platform", stats);
        return out;
    }

    /**
     * 设置一个 agent 的运转状态。
     *
     * <p>认不出或没给的 {@code lifecycle} 一律**拒绝**, 而不是"猜一个默认值" —— 一个
     * 打错的枚举值若被当成 {@code active}, 症状是"我明明按了停, 它还在烧 token",
     * 而没有人会去怀疑那个请求体。
     */
    @PutMapping("/{agentId}/lifecycle")
    public ResponseEntity<Map<String, Object>> setLifecycle(@PathVariable String agentId,
                                                            @RequestBody SetLifecycleBody body) {
        String userId = currentUser.requireUserId();
        AgentLifecycle target = parse(body == null ? null : body.lifecycle());
        if (target == null) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "lifecycle 必须是 active 或 paused",
                    "hint", "{\"lifecycle\":\"paused\"} 停, {\"lifecycle\":\"active\"} 继续"));
        }
        // 先过归属: 不属于调用者时这里直接 404, 与其余 /api/companions 接口同一个口径
        Companion c = companionService.requireOwned(userId, agentId);
        boolean changed = target.isPaused()
                ? agentSwitch.pause(c.getId())
                : agentSwitch.resume(c.getId());

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("agentId", c.getId());
        out.put("lifecycle", target.wire());
        out.put("changed", changed);
        return ResponseEntity.ok(out);
    }

    /**
     * 把**我自己**的全部 agent 停下来 —— 用户级的一键关停。
     *
     * <p>它不懂"平台里有多少 agent", 只懂"我有哪些" —— 那正是这一面能回答的问题。
     */
    @PostMapping("/lifecycle/pause-all")
    public Map<String, Object> pauseAllMine() {
        String userId = currentUser.requireUserId();
        // 名单只取一次。第二次调用不只是一次多余的查询: 两次之间列表可能变了
        // (用户同时在别处建了一个 agent), 于是 "total" 会大于本次真正遍历过的行数 ——
        // 一个自己跟自己不一致的响应。
        List<Companion> mine = companionService.list(userId);
        int changed = 0;
        for (Companion c : mine) {
            if (agentSwitch.pause(c.getId())) changed++;
        }
        return Map.of("paused", changed, "total", mine.size());
    }

    /** {@link #pauseAllMine()} 的反面。 */
    @PostMapping("/lifecycle/resume-all")
    public Map<String, Object> resumeAllMine() {
        String userId = currentUser.requireUserId();
        List<Companion> mine = companionService.list(userId);
        int changed = 0;
        for (Companion c : mine) {
            if (agentSwitch.resume(c.getId())) changed++;
        }
        return Map.of("resumed", changed, "total", mine.size());
    }

    /** 给控制台表格的一行。字段名与 {@code CompanionDto} 对齐, 前端不必记两套。 */
    private static Map<String, Object> row(Companion c) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("agentId", c.getId());
        m.put("name", c.getName());
        m.put("lifecycle", AgentLifecycle.of(c.getStatus()).wire());
        m.put("paused", c.isPaused());
        return m;
    }

    /**
     * 宽松解析请求体 —— 但**只对大小写宽松**, 不对取值宽松。
     *
     * <p>{@code "PAUSED"} 与 {@code "Paused"} 都接受(那是打字习惯),
     * 而 {@code "stop"} / {@code ""} / {@code null} 一律返回 null 让调用方回 400。
     */
    private static AgentLifecycle parse(String raw) {
        if (raw == null || raw.isBlank()) return null;
        String v = raw.trim();
        for (AgentLifecycle l : AgentLifecycle.values()) {
            if (l.wire().equalsIgnoreCase(v)) return l;
        }
        return null;
    }

    public static class SetLifecycleBody {
        private String lifecycle;

        public String lifecycle() {
            return lifecycle;
        }

        public void setLifecycle(String lifecycle) {
            this.lifecycle = lifecycle;
        }
    }
}
