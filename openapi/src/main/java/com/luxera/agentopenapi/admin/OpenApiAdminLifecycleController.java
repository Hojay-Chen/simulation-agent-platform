package com.luxera.agentopenapi.admin;

import com.luxera.companion.persona.AgentSwitchService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Agent 开关的**运维面** —— 平台级的总闸, 吃 {@code X-Admin-Key}。
 *
 * <h2>为什么"全部关掉"必须在这一面</h2>
 *
 * 控制台那一面(8091 的 {@code /api/agents/lifecycle})是用户 JWT, 它回答"**我**的
 * agent 什么状态"。而"把这个部署上所有 agent 都停下来"是一个**跨用户**的动作:
 *
 * <ul>
 *   <li>放在用户面上 → 任何登录用户都能让别人的 agent 停工。这是权限错误, 不是便利。</li>
 *   <li>放在客户端面上({@code Bearer sap_...}) → 一个客户端只能碰自己的 agent, 于是
 *       "全部"这个词根本没有主语。</li>
 * </ul>
 *
 * <p>所以它在管理密钥面, 与"发客户端钥"同一个门 —— 两者都是平台管理员做的、
 * 影响面超出自己账户的动作。
 *
 * <h2>它是怎么被用起来的</h2>
 *
 * 这个平台的 agent 是**持续运转**的, 于是"开发测试做完了, 把全部 agent 关掉免得
 * 白烧 token"是一个会反复出现的操作。手工在控制台点 110 次不是一个操作,
 * 是一个下午。所以这里给出一条命令就做完的路:
 *
 * <pre>
 *   curl -X POST http://127.0.0.1:8092/api/v1/openapi/admin/agents/pause-all \
 *        -H "X-Admin-Key: $OPENAPI_ADMIN_KEY"
 * </pre>
 *
 * <p>{@code scripts/agents-off.sh} 与 {@code agents-on.sh} 就是这两条命令的封装。
 *
 * <h2>为什么还有 {@code runtimeEnabled}</h2>
 *
 * 那是部署级的总闸({@code app.agent.runtime-enabled}), 关掉它需要改配置并重启。
 * 它与这里的关系是**或**: 任意一个说停就停。两者的分工是 —— 这里改的是数据
 * (立即生效、在控制台上可见、重启后仍然有效), 那个改的是进程(整台机器一起停,
 * 用于维护窗口, 且不需要先知道有哪些 agent)。
 */
@Slf4j
@Tag(name = "admin", description = "平台运维动作(管理密钥面)")
@RestController
@RequestMapping("/api/v1/openapi/admin")
public class OpenApiAdminLifecycleController {

    private final AgentSwitchService agentSwitch;

    public OpenApiAdminLifecycleController(AgentSwitchService agentSwitch) {
        this.agentSwitch = agentSwitch;
    }

    /** 全平台口径的状态 —— 关掉之前/之后用来确认的地方。 */
    @Operation(summary = "Agent 开关全景: 平台统计 + 被拦下的 LLM 调用")
    @GetMapping("/agents/lifecycle")
    public Map<String, Object> overview() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("platform", agentSwitch.stats());
        out.put("hint", "POST /api/v1/openapi/admin/agents/pause-all 停全部; resume-all 恢复");
        return out;
    }

    /**
     * 暂停全平台所有活着的 agent。
     *
     * <p>返回 {@code changed} 与 {@code alreadyPaused} 两个数, 而不是一个"总数":
     * 重复调用时前者是 0, 而后者一直是 110 —— 两个数放在一起, "我刚才是又停了一遍
     * 还是第一次停"一眼可辨。幂等操作最需要的正是这个区分。
     */
    @Operation(summary = "暂停全部 agent(幂等)")
    @PostMapping("/agents/pause-all")
    public Map<String, Object> pauseAll() {
        int before = agentSwitch.stats().paused();
        int changed = agentSwitch.pauseAll();
        AgentSwitchService.Stats after = agentSwitch.stats();
        log.warn("[AgentSwitch] 运维面: pause-all changed={} 现在 paused={}", changed, after.paused());
        return result("paused", before, changed, after);
    }

    /** {@link #pauseAll()} 的反面。 */
    @Operation(summary = "恢复全部 agent(幂等)")
    @PostMapping("/agents/resume-all")
    public Map<String, Object> resumeAll() {
        int before = agentSwitch.stats().paused();
        int changed = agentSwitch.resumeAll();
        AgentSwitchService.Stats after = agentSwitch.stats();
        log.warn("[AgentSwitch] 运维面: resume-all changed={} 现在 paused={}", changed, after.paused());
        return result("resumed", before, changed, after);
    }

    private static Map<String, Object> result(String verb, int before, int changed,
                                              AgentSwitchService.Stats after) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("action", verb);
        out.put("changed", changed);
        out.put("pausedBefore", before);
        out.put("platform", after);
        return out;
    }
}
