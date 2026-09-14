package com.luxera.companion.eval;

import com.luxera.companion.config.CurrentUser;
import com.luxera.companion.contracts.api.ConversationView;
import com.luxera.companion.contracts.api.MessageView;
import com.luxera.companion.contracts.spi.ChatWorldPort;
import com.luxera.companion.runtime.pipeline.PendingMessageStateRepository;
import com.luxera.companion.thought.ThoughtRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;

/**
 * §71/§72 Anti-AI Evaluation 诊断端点:
 * 统计该伴侣的行为指标并评估是否存在反 AI 模式。
 * 用于开发/验收时观察"真人感"。
 */
@RestController
@RequestMapping("/api/companions/{companionId}/v6/eval")
public class AntiAIController {

    private final AntiAIPatternEvaluator evaluator;
    private final ChatWorldPort chatWorld;
    private final PendingMessageStateRepository pendingRepo;
    private final ThoughtRepository thoughtRepo;

    public AntiAIController(AntiAIPatternEvaluator evaluator, ChatWorldPort chatWorld,
                            PendingMessageStateRepository pendingRepo, ThoughtRepository thoughtRepo) {
        this.evaluator = evaluator;
        this.chatWorld = chatWorld;
        this.pendingRepo = pendingRepo;
        this.thoughtRepo = thoughtRepo;
    }

    @GetMapping
    public AntiAIPatternEvaluator.EvalResult evaluate(@PathVariable String companionId, CurrentUser user) {
        List<String> convIds = chatWorld.conversationsOf(companionId)
                .stream().map(ConversationView::getId).toList();

        long totalReplies = 0;
        long instantReplies = 0;
        long proactive = 0;
        long ended = 0;
        long totalChars = 0;
        List<String> allMessages = new ArrayList<>();

        for (String convId : convIds) {
            for (MessageView m : chatWorld.messages(convId)) {
                if (!"companion".equals(m.getSenderType())) continue;
                totalReplies++;
                if ("SHORT_ACK".equals(m.getMessageKind())) instantReplies++;
                if ("PROACTIVE".equals(m.getMessageKind())) proactive++;
                if (m.getContent() != null) {
                    totalChars += m.getContent().length();
                    if (m.getContent().contains("END_CONVERSATION")) ended++;
                    allMessages.add(m.getContent());
                }
            }
        }

        long deferred = pendingRepo.findByCompanionIdAndStatus(companionId, "PENDING").size();
        long ignored = pendingRepo.findByCompanionIdAndStatus(companionId, "EXPIRED").size();
        long forget = thoughtRepo.countByCompanionIdAndStatus(companionId, "EXPIRED");

        // 连发检测: 相邻两条 companion 消息间隔 < 60s 且同会话 → 多段
        long multiMessage = 0;
        for (String convId : convIds) {
            List<MessageView> msgs = chatWorld.messages(convId);
            for (int i = 1; i < msgs.size(); i++) {
                if (!"companion".equals(msgs.get(i).getSenderType())) continue;
                MessageView prev = msgs.get(i - 1);
                if ("companion".equals(prev.getSenderType())
                        && prev.getCreatedAt() != null && msgs.get(i).getCreatedAt() != null
                        && java.time.Duration.between(prev.getCreatedAt(), msgs.get(i).getCreatedAt()).toSeconds() < 60) {
                    multiMessage++;
                }
            }
        }

        double avgLen = totalReplies == 0 ? 0 : (double) totalChars / totalReplies;

        AntiAIPatternEvaluator.BehaviorStats stats = new AntiAIPatternEvaluator.BehaviorStats(
                totalReplies, instantReplies, deferred, ignored, proactive,
                ended, multiMessage, forget, avgLen);
        return evaluator.evaluate(stats);
    }
}
