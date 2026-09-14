package com.luxera.companion.runtime;

import com.luxera.companion.config.CurrentUser;
import com.luxera.companion.contracts.api.ConversationView;
import com.luxera.companion.contracts.api.MessageAppendCommand;
import com.luxera.companion.contracts.api.MessageView;
import com.luxera.companion.contracts.spi.ChatWorldPort;
import com.luxera.companion.contracts.spi.CompanionDirectoryPort;
import com.luxera.companion.persona.Companion;
import com.luxera.companion.persona.CompanionService;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * V10 §2 — the digital-human platform's half of the chat REST surface.
 *
 * <p>The chat platform owns conversation storage; this controller owns the two operations that
 * only make sense once a companion exists:
 *
 * <ul>
 *   <li>{@code POST /first} — open the thread for a companion, letting <em>her</em> say the first
 *       line. The greeting is hers, so it is appended as a companion message into a conversation
 *       created by the chat platform.</li>
 *   <li>{@code POST /{conversationId}/chat} — a streaming compatibility endpoint. The modern client
 *       posts to {@code /messages} and watches {@code /events}; this endpoint exists for scripts and
 *       older clients and bridges the same behaviour: it writes the human's messages into the world,
 *       wakes the digital human, then tails the conversation for her reply.</li>
 * </ul>
 *
 * <p>Everything it touches goes through {@link ChatWorldPort}. It never sees a chat entity, a
 * repository or a transaction — those live behind the port.
 */
@Slf4j
@RestController
@RequestMapping("/api/companions/{companionId}/conversations")
public class ChatStreamController {

    /** 等待她回复的上限(秒); 超时按"这次没回"处理, 与真人一样 */
    private static final long REPLY_TIMEOUT_SECONDS = 90;
    private static final long POLL_INTERVAL_MS = 300;

    private final CurrentUser currentUser;
    private final CompanionDirectoryPort companionDirectory;
    private final CompanionService companionService;
    private final ChatWorldPort chatWorld;
    private final AgentRuntime agentRuntime;

    /**
     * SSE 尾巴轮询用的执行器。这不是业务线程池 —— 它只做"等消息出现"的等待,
     * 认知与生成都在 AgentRuntime 自己的 Person Actor 里跑。
     */
    private final ExecutorService sseExecutor = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "chat-sse-tail");
        t.setDaemon(true);
        return t;
    });

    public ChatStreamController(CurrentUser currentUser,
                                CompanionDirectoryPort companionDirectory,
                                CompanionService companionService,
                                ChatWorldPort chatWorld,
                                AgentRuntime agentRuntime) {
        this.currentUser = currentUser;
        this.companionDirectory = companionDirectory;
        this.companionService = companionService;
        this.chatWorld = chatWorld;
        this.agentRuntime = agentRuntime;
    }

    /**
     * 打开(或复用)与该伴侣的会话。首次创建时由她先说第一句 —— 问候语存在伴侣身上,
     * 只有数字人平台知道它是什么。
     */
    @PostMapping("/first")
    public ConversationView first(@PathVariable String companionId) {
        String userId = currentUser.requireUserId();
        CompanionDirectoryPort.CompanionRef ref = companionDirectory.requireOwned(userId, companionId);
        ConversationView conv = chatWorld.ensureConversation(userId, companionId, ref.name());

        if (chatWorld.messages(conv.getId()).isEmpty()) {
            Companion companion = companionService.requireOwned(userId, companionId);
            String greeting = companion.getGreeting();
            if (greeting != null && !greeting.isBlank()) {
                try {
                    chatWorld.append(MessageAppendCommand
                            .of(conv.getId(), "companion", greeting)
                            .withKind("NORMAL")
                            .withIdempotencyKey("greeting-" + conv.getId()));
                } catch (Exception e) {
                    log.warn("[ChatStream] 问候语写入失败: companion={}, error={}", companionId, e.getMessage());
                }
            }
        }
        return chatWorld.conversation(conv.getId()).orElse(conv);
    }

    /**
     * 流式聊天(SSE, 兼容端点): 批量消息 → 写入世界 → 唤醒她 → 尾巴轮询她的回复。
     *
     * <p>与旧实现的区别: 回复不再在这个请求线程里同步生成。消息先成为事实, 她的认知在
     * Person Actor 里跑, 这里只是把它"看"出来 —— 她没有回, 这里也不会凭空造一句。
     */
    @PostMapping(value = "/{conversationId}/chat", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter chat(@PathVariable String companionId, @PathVariable String conversationId,
                           @RequestBody(required = false) ChatRequest req) {
        String userId = currentUser.requireUserId();
        companionDirectory.requireOwned(userId, companionId);
        ConversationView conv = chatWorld.conversation(conversationId)
                .orElseThrow(() -> new IllegalArgumentException("会话不存在"));
        if (!companionId.equals(conv.getCompanionId())) {
            throw new IllegalArgumentException("会话与伴侣不匹配");
        }
        List<String> contents = resolveContents(req);
        if (contents.isEmpty()) {
            throw new IllegalArgumentException("消息不能为空");
        }

        SseEmitter emitter = new SseEmitter(300_000L);
        sseExecutor.execute(() -> streamChat(emitter, userId, companionId, conversationId, contents));
        return emitter;
    }

    private void streamChat(SseEmitter emitter, String userId, String companionId,
                            String conversationId, List<String> contents) {
        try {
            LocalDateTime startedAt = LocalDateTime.now();

            // 1. 批量写入世界(幂等键=会话+序号+内容指纹, 重放不会重复发帖)
            List<MessageView> sent = new ArrayList<>();
            int seq = 0;
            for (String content : contents) {
                MessageView m = chatWorld.append(MessageAppendCommand
                        .of(conversationId, "user", content)
                        .withIdempotencyKey("chat-" + conversationId + "-" + seq + "-" + content.hashCode()));
                if (m != null) sent.add(m);
                seq++;
            }
            if (sent.isEmpty()) {
                send(emitter, "done", Map.of("ignored", true, "reason", "空消息"));
                emitter.complete();
                return;
            }

            // 2. 唤醒她 —— 火忘式, 认知在她的线程里跑
            agentRuntime.submit(userId, companionId, conversationId, sent);

            // 3. 尾巴轮询: 她说了就推给客户端, 没说就是没说
            long deadline = System.currentTimeMillis() + REPLY_TIMEOUT_SECONDS * 1000;
            String lastUserId = sent.get(sent.size() - 1).getId();
            while (System.currentTimeMillis() < deadline) {
                List<MessageView> after = messagesAfter(conversationId, lastUserId);
                if (!after.isEmpty()) {
                    for (MessageView reply : after) {
                        send(emitter, "token", Map.of("delta", reply.getContent()));
                        send(emitter, "message", Map.of(
                                "messageId", reply.getId(),
                                "content", reply.getContent(),
                                "messageKind", reply.getMessageKind() == null ? "NORMAL" : reply.getMessageKind()));
                    }
                    send(emitter, "done", Map.of("messageId", after.get(after.size() - 1).getId()));
                    emitter.complete();
                    return;
                }
                Thread.sleep(POLL_INTERVAL_MS);
            }

            // 4. 超时: 她这次没回(忙/没看到/决定不回) —— 这不是错误
            send(emitter, "done", Map.of("ignored", true, "reason", "她还没回"));
            emitter.complete();
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            emitter.complete();
        } catch (Exception e) {
            log.error("[ChatStream] 流式处理失败", e);
            try {
                emitter.send(SseEmitter.event().name("error")
                        .data(Map.of("message", "生成回复时出错,请重试")));
            } catch (IOException ignored) {
                // 客户端已断开
            }
            emitter.complete();
        }
    }

    /** 会话里在该用户消息之后出现的伴侣消息(升序) */
    private List<MessageView> messagesAfter(String conversationId, String userMessageId) {
        List<MessageView> all = chatWorld.messages(conversationId);
        int idx = -1;
        for (int i = 0; i < all.size(); i++) {
            if (userMessageId.equals(all.get(i).getId())) {
                idx = i;
                break;
            }
        }
        if (idx < 0) return List.of();
        List<MessageView> out = new ArrayList<>();
        for (int i = idx + 1; i < all.size(); i++) {
            if (all.get(i).isFromCompanion()) out.add(all.get(i));
        }
        return out;
    }

    private static List<String> resolveContents(ChatRequest req) {
        List<String> out = new ArrayList<>();
        if (req == null) return out;
        if (req.getMessages() != null && !req.getMessages().isEmpty()) {
            for (ChatMessageItem item : req.getMessages()) {
                if (item == null || item.getContent() == null || item.getContent().isBlank()) continue;
                out.add(item.getContent().trim());
            }
            return out;
        }
        if (req.getContent() != null && !req.getContent().isBlank()) {
            out.add(req.getContent().trim());
        }
        return out;
    }

    private static void send(SseEmitter emitter, String event, Object data) throws IOException {
        emitter.send(SseEmitter.event().name(event).data(data));
    }

    @Data
    public static class ChatRequest {
        /** 单条消息(兼容) */
        private String content;
        /** 批量消息(连发归并) */
        private List<ChatMessageItem> messages;
    }

    @Data
    public static class ChatMessageItem {
        private String content;
    }
}
