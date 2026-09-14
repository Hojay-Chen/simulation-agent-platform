package com.luxera.companion.digitalhuman.conversation;

import com.luxera.companion.llm.ChatResult;
import com.luxera.companion.llm.LlmRouter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * V10 §15 Conversation Runtime 测试: 唯一文本生成管道 ——
 * 契约解析 + 质量闸门 + 失败重生成(≤2 次)。
 */
@ActiveProfiles("test")
@SpringBootTest
class ConversationRuntimeTest {

    @Autowired
    ConversationRuntime conversationRuntime;

    @MockBean
    LlmRouter llm;

    private ConversationRequest request() {
        return ConversationRequest.builder("p1", "小满", "现在给用户回消息吧")
                .stable("你是小满,一个真实的人。")
                .stable("请自然说话, 像发微信。")
                .semiStable("关系: 熟悉。")
                .dynamic("她刚看到消息: 周末去爬山吗")
                .build();
    }

    @BeforeEach
    void setUp() {
        // 默认返回契约化输出
        when(llm.chat(any())).thenReturn(new ChatResult(
                "{\"messages\":[{\"text\":\"周末去爬山?好呀,我想去\"}]}", "mock", 0, 0, "mock"));
    }

    @Test
    void validContractOutputReturnsDrafts() {
        List<ChatMessageDraft> drafts = conversationRuntime.generateDrafts(request());
        assertEquals(1, drafts.size());
        assertEquals("周末去爬山?好呀,我想去", drafts.get(0).text());
    }

    @Test
    void narrationOutputIsRegenerated() {
        // 第一次输出含旁白(未通过质量闸门) → 自动重试; 第二次通过
        when(llm.chat(any()))
                .thenReturn(new ChatResult("{\"messages\":[{\"text\":\"（笑了笑）周末去爬山?好呀\"}]}",
                        "mock", 0, 0, "mock"))
                .thenReturn(new ChatResult("{\"messages\":[{\"text\":\"周末去爬山?好呀,我想去\"}]}",
                        "mock", 0, 0, "mock"));

        List<ChatMessageDraft> drafts = conversationRuntime.generateDrafts(request());
        assertEquals(1, drafts.size(), "重试后应返回通过的草稿");
        assertEquals("周末去爬山?好呀,我想去", drafts.get(0).text());
        verify(llm, times(2)).chat(any());
    }

    @Test
    void persistentNarrationGivesUp() {
        when(llm.chat(any())).thenReturn(new ChatResult(
                "{\"messages\":[{\"text\":\"（沉默片刻后）你说得对\"}]}", "mock", 0, 0, "mock"));

        List<ChatMessageDraft> drafts = conversationRuntime.generateDrafts(request());
        assertTrue(drafts.isEmpty(), "始终不过闸门 → 不生成(像真人没说出口)");
        verify(llm, times(ConversationRuntime.MAX_ATTEMPTS)).chat(any());
    }

    @Test
    void multiMessageContractReturnsAllDrafts() {
        when(llm.chat(any())).thenReturn(new ChatResult(
                "{\"messages\":[{\"text\":\"第一条\"},{\"text\":\"第二条\"}]}", "mock", 0, 0, "mock"));
        List<ChatMessageDraft> drafts = conversationRuntime.generateDrafts(request());
        assertEquals(2, drafts.size());
        assertEquals("第一条", drafts.get(0).text());
        assertEquals("第二条", drafts.get(1).text());
    }

    @Test
    void llmFailureReturnsEmpty() {
        when(llm.chat(any())).thenThrow(new RuntimeException("LLM 超时"));
        assertTrue(conversationRuntime.generateDrafts(request()).isEmpty());
    }
}
