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
                "name", c.getName(),
                "peerMemberId", c.getId()));
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
