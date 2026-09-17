package com.luxera.companion.maintenance;

import com.luxera.companion.persona.Companion;
import com.luxera.companion.persona.CompanionRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 一次性对账: 把**历史上**删除的 Agent 遗留的残骸补齐清理掉。
 *
 * <h2>为什么需要它, 而不是"新代码修好了就行"</h2>
 *
 * 在 {@code CompanionService#delete} 接上清理之前, 「删除 Agent」只做了一件事: 给
 * {@code companions.deleted_at} 写个时间戳。它从不动聊天平台那边 —— 于是 2026-09-17
 * 用户看到的「同一个 agent 出现多个聊天窗口」, 底下是 38 个已删除 Agent 留下的 36 段
 * 幽灵会话(连着 238 条消息), 而名字都是 LLM 从描述里生成的, 反复收敛到同一个「小满」,
 * 看起来就像"同一个账号开了 36 个窗口"。
 *
 * <p>修好 {@code delete()} 只能保证**以后**不再产生幽灵。已经躺在那里的那些不会自己消失,
 * 因为触发清理所需的那个动作(一次删除)已经发生过了。所以要有这么一次回溯。
 *
 * <h2>为什么值得留在仓库里</h2>
 *
 * 它同时也是"删除做到一半"(本仓删成了、聊天平台那次调用失败)的**批量修复工具** —— 那种
 * 情况在单条上是靠再点一次删除解决的({@code CompanionController#delete} 的 {@code ownsDeleted}
 * 分支), 但如果失败发生在一次部署切换中间, 攒下几十条, 逐个点就不现实了。
 *
 * <h2>为什么默认不跑</h2>
 *
 * 它是启动钩子, 而它做的是**跨服务、不可逆、会遍历全表**的硬删除。这种东西绝不能因为
 * 一次常规重启就自己跑起来 —— 开关是显式的, 跑完就该关掉:
 *
 * <pre>
 *   # 只跑一次
 *   APP_MAINTENANCE_PURGE_GHOST_CHATS=true   # 见 application.yml 的 app.maintenance.*
 * </pre>
 *
 * <p>幂等: 已经清干净的 Agent 再走一遍只会拿到空清单。失败逐条记录不中断 —— 一个 Agent 的
 * 清理失败不该让后面几十个都不做。
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "app.maintenance.purge-ghost-chats", havingValue = "true")
public class GhostChatSweeper implements ApplicationRunner {

    private final CompanionRepository companions;
    private final AgentRetirementService retirement;

    public GhostChatSweeper(CompanionRepository companions, AgentRetirementService retirement) {
        this.companions = companions;
        this.retirement = retirement;
    }

    @Override
    public void run(ApplicationArguments args) {
        List<Companion> ghosts = companions.findByDeletedAtIsNotNull();
        log.warn("[GhostSweeper] 开始回溯清理 {} 个已删除 Agent 的残骸 —— 这会**不可逆地**"
                + "删除它们在聊天平台的会话与消息", ghosts.size());

        int cleaned = 0;
        int failed = 0;
        for (Companion c : ghosts) {
            try {
                // 先本地活队列(自己的事务), 再跨服务销毁(不可逆)。顺序与 CompanionController
                // 的删除路径一致, 理由也一致 —— 见 AgentRetirementService 的类注释。
                retirement.clearActiveQueues(c.getId());
                retirement.purgeChatWorld(c.getId());
                cleaned++;
            } catch (Exception e) {
                failed++;
                log.warn("[GhostSweeper] Agent {} 清理失败(继续下一个): {}", c.getId(), e.toString());
            }
        }

        if (failed == 0) {
            log.warn("[GhostSweeper] 完成: {} 个已删除 Agent 的残骸全部清干净。"
                    + "**请把 app.maintenance.purge-ghost-chats 关掉** —— 它不该跟着每次重启跑。",
                    cleaned);
        } else {
            log.error("[GhostSweeper] 完成: 成功 {} 个, 失败 {} 个。失败的多半是聊天平台当时不可达;"
                    + "保持开关打开并重启一次即可重试(幂等), 或稍后开一次。",
                    cleaned, failed);
        }
    }
}
