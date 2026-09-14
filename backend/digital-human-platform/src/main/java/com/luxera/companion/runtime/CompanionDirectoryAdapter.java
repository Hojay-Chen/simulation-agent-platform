package com.luxera.companion.runtime;

import com.luxera.companion.contracts.api.MessageView;
import com.luxera.companion.contracts.spi.CompanionDirectoryPort;
import com.luxera.companion.persona.Companion;
import com.luxera.companion.persona.CompanionService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * In-process implementation of {@link CompanionDirectoryPort} — the single seam through which the
 * chat platform reaches the digital-human platform.
 *
 * <p>Exposes exactly two things: whether a human owns a companion (a lookup the chat platform
 * cannot do itself, because companions live here), and the fact that a human said something.
 * Everything the digital human then does — perceive, appraise, ignore, defer, reply — happens
 * behind {@link AgentRuntime#submit} and is never visible to the caller.
 *
 * <p>In a split deployment this bean is replaced by a DHCP-over-WebSocket adapter and no chat
 * code changes.
 */
@Slf4j
@Component
public class CompanionDirectoryAdapter implements CompanionDirectoryPort {

    private final CompanionService companionService;
    private final AgentRuntime agentRuntime;

    public CompanionDirectoryAdapter(CompanionService companionService, AgentRuntime agentRuntime) {
        this.companionService = companionService;
        this.agentRuntime = agentRuntime;
    }

    @Override
    public CompanionRef requireOwned(String userId, String companionId) {
        Companion c = companionService.requireOwned(userId, companionId);
        // peerMemberId: 会话参与者里"非人类那一侧"的 id。今天是 companionId 本身;
        // 配对制全面铺开后换成 simulator 账号 id —— chat 只是存着, 从不解释它。
        return new CompanionRef(c.getId(), c.getName(), c.getId());
    }

    @Override
    public void onUserMessage(String userId, String companionId, String conversationId,
                              List<MessageView> messages) {
        // 火忘式: 认知在数字人自己的线程池里跑, 聊天请求线程不等它
        agentRuntime.submit(userId, companionId, conversationId, messages);
    }
}
