package com.luxera.agentserver.internal;

import com.luxera.companion.contracts.api.MessageView;
import com.luxera.companion.runtime.AgentRuntime;
import com.luxera.companion.persona.Companion;
import com.luxera.companion.persona.CompanionService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 仿真 Agent 平台对 chat 平台开放的服务间端点 —— {@code CompanionDirectoryPort} 的
 * HTTP 面。chat 侧 {@code HttpCompanionDirectoryAdapter} 调用这里。
 *
 * <p>只有两条路:
 * <ul>
 *   <li>{@code require-owned}: chat 在把一条用户消息落库前问"这个用户真的拥有这个伴侣吗"。
 *     答 404 时 chat 侧把这次写入拒掉（{@code IllegalArgumentException}），绝不放行。</li>
 *   <li>{@code on-user-message}: fire-and-forget。chat 落完消息就走, 数字人自己决定感知、
 *     忽略、延后或回复 —— 202 Accepted, 认知在 {@link AgentRuntime} 自己的线程池跑。</li>
 * </ul>
 *
 * <p>没有 GET/读列表之类 —— {@code CompanionDirectoryPort} 刻意只暴露这两个能力
 * (V10 §2.1 "聊天平台对数字人的认知止于 opaque facts + 一次 fire-and-forget 通知"),
 * HTTP 面不多开一扇窗。
 */
@RestController
@RequestMapping("/internal/directory")
public class InternalCompanionDirectoryController {

    private final CompanionService companionService;
    private final AgentRuntime agentRuntime;

    public InternalCompanionDirectoryController(CompanionService companionService,
                                                AgentRuntime agentRuntime) {
        this.companionService = companionService;
        this.agentRuntime = agentRuntime;
    }

    /**
     * chat 落用户消息前的归属校验。404 = 伴侣不存在或不属于这个用户 —— chat 侧据此
     * 拒掉这次写入（不会放行"陌生人的伴侣"）。
     *
     * <h2>{@code peerMemberId} 现在给的是聊天账号 id, 不再是 companion id</h2>
     *
     * 它的语义从第一天起就是"这个 Agent 在聊天平台上的**不透明参与者 id**"
     * ({@code CompanionDirectoryPort.CompanionRef#peerMemberId} 的 Javadoc 逐字承诺了这次切换:
     * "today the companion id, under a provisioned simulator account once pairing is universal")。
     * 现在账号铸好了, 于是承诺兑现 —— 有 {@code chat_account_id} 就给它, 没有才退回
     * companion id。
     *
     * <p><b>回退那一支不是过渡期的将就。</b> 一个还没有聊天账号的 Agent 至今是正常状态
     * (账号是这一期才补铸的), 而如果这里对它返回一个空值或抛错, 那个 Agent 的每一条消息都
     * 会写不进去。回退值 {@code companionId} 又正好等于切换之前的行为, 所以"半迁移"状态下
     * 系统是逐字可用的。
     *
     * <p>为什么是这一侧决定而不是让 chat 侧自己查设备表: 因为**这一侧才知道该用哪个账号**
     * —— {@code chat_account_id} 是本表的一列。让对面去查它的设备表, 等于让两份记录各自
     * 回答同一个问题, 而它们会漂移。真实的分工是: 账号由 chat 铸(它是 chat 的 users 行),
     * 铸完**告诉**本仓, 本仓记下来, 此后"这个 agent 用哪个账号"由本仓回答。
     */
    @PostMapping("/require-owned")
    public ResponseEntity<Map<String, String>> requireOwned(@RequestBody RequireOwnedBody body) {
        Companion c;
        try {
            c = companionService.requireOwned(body.userId(), body.companionId());
        } catch (RuntimeException e) {
            // CompanionService 内部区分 EntityNotFound / BusinessException, 对外一律 404
            // —— 存在与不存在的区分在 HTTP 边界上不再泄漏。
            return ResponseEntity.status(404).build();
        }
        return ResponseEntity.ok(Map.of(
                "companionId", c.getId(),
                "name", c.getName() == null ? "" : c.getName(),
                "peerMemberId", peerMemberIdOf(c)));
    }

    /** 有聊天账号就用它, 没有就退回 companion id —— 见 {@link #requireOwned} 的说明。 */
    private static String peerMemberIdOf(Companion c) {
        String accountId = c.getChatAccountId();
        return accountId != null && !accountId.isBlank() ? accountId : c.getId();
    }

    /**
     * 人类说了一句话 —— fire-and-forget。chat 落完消息立刻返回, 认知在 DH 自己线程池。
     * 202 Accepted: "收到了, 处不处理、怎么处理是我的事"。
     */
    @PostMapping("/on-user-message")
    public ResponseEntity<Void> onUserMessage(@RequestBody OnUserMessageBody body) {
        agentRuntime.submit(body.userId(), body.companionId(), body.conversationId(),
                body.messages() == null ? List.of() : body.messages());
        return ResponseEntity.accepted().build();
    }

    public record RequireOwnedBody(String userId, String companionId) {}
    public record OnUserMessageBody(String userId, String companionId, String conversationId,
                                    List<MessageView> messages) {}
}
