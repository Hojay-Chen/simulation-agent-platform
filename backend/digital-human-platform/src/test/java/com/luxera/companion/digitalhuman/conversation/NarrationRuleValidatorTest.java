package com.luxera.companion.digitalhuman.conversation;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * V10 §15.3 输出验证器测试: 旁白/舞台动作/AI 腔必须被拦截。
 * 目标: 用户无法区分聊天对象是真人还是 agent —— 这些信号是"非真人"铁证。
 */
class NarrationRuleValidatorTest {

    private final NarrationRuleValidator validator = new NarrationRuleValidator();

    @Test
    void rejectsParenStageDirections() {
        // 括号舞台动作(中英文)
        assertNotNull(validator.validate(ChatMessageDraft.of(0, "（放下笔笑了笑）你今天怎么样")));
        assertNotNull(validator.validate(ChatMessageDraft.of(0, "(笑) 当然好啊")));
        assertNotNull(validator.validate(ChatMessageDraft.of(0, "（叹了口气）我有点累")));
    }

    @Test
    void rejectsMarkdownActions() {
        assertNotNull(validator.validate(ChatMessageDraft.of(0, "*smiles* 你说呢")));
        assertNotNull(validator.validate(ChatMessageDraft.of(0, "_whispers_ 别告诉别人")));
        assertNotNull(validator.validate(ChatMessageDraft.of(0, "~歪头~ 是吗")));
    }

    @Test
    void rejectsNarrationPrefixes() {
        assertNotNull(validator.validate(ChatMessageDraft.of(0, "她想了想, 然后说: 好啊")));
        assertNotNull(validator.validate(ChatMessageDraft.of(0, "沉默片刻后, 我回答: 嗯")));
        assertNotNull(validator.validate(ChatMessageDraft.of(0, "（她微微一笑）那就这么定")));
    }

    @Test
    void rejectsAiTells() {
        assertNotNull(validator.validate(ChatMessageDraft.of(0, "作为AI, 我无法体验情绪")));
        assertNotNull(validator.validate(ChatMessageDraft.of(0, "作为一个语言模型, 我不能回答这个问题")));
        assertNotNull(validator.validate(ChatMessageDraft.of(0, "抱歉, 我无法做到这一点")));
    }

    @Test
    void acceptsNaturalHumanText() {
        // 真人聊天: 无括号动作/无旁白/无 AI 腔
        assertNull(validator.validate(ChatMessageDraft.of(0, "刚忙完, 你今天怎么样?")));
        assertNull(validator.validate(ChatMessageDraft.of(0, "哈哈哈哈真的假的, 太搞笑了")));
        assertNull(validator.validate(ChatMessageDraft.of(0, "嗯...让我想想, 周末去看电影吧")));
        assertNull(validator.validate(ChatMessageDraft.of(0, "我在煮面呢, 一会儿聊")));
    }

    @Test
    void validateAllReportsIssueIndexes() {
        List<ChatMessageDraft> drafts = List.of(
                ChatMessageDraft.of(0, "正常消息"),
                ChatMessageDraft.of(1, "（笑）这条不行"),
                ChatMessageDraft.of(2, "另一条正常消息"));
        List<ConversationOutputValidator.Issue> issues = validator.validateAll(drafts);
        assertEquals(1, issues.size());
        assertEquals(1, issues.get(0).draftIndex());
    }
}
