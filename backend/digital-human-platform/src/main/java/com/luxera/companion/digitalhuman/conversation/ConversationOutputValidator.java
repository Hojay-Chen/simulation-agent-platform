package com.luxera.companion.digitalhuman.conversation;

import java.util.List;

/**
 * V10 §15.3 ConversationOutputValidator: 最终聊天文本的唯一质量闸门。
 *
 * 系统中只有 Conversation Runtime 可以生成聊天文本; 生成后的草稿必须通过
 * 验证器(规则 + 可选分类器)才能发送。失败 → 重新生成, 最多 N 次。
 *
 * 目标: 用户无法区分聊天对象是真人还是 agent —— 输出中禁止出现
 * 旁白/舞台动作/AI 腔等一切"不是真人说话"的信号。
 */
public interface ConversationOutputValidator {

    /** 校验一条消息草稿; 返回 null = 通过, 非 null = 未通过原因 */
    String validate(ChatMessageDraft draft);

    /**
     * 校验一批草稿(连发消息); 返回未通过的草稿索引 + 原因。
     * 全部通过时返回空列表。
     */
    default List<Issue> validateAll(List<ChatMessageDraft> drafts) {
        if (drafts == null) return List.of();
        return drafts.stream()
                .map(d -> {
                    String reason = validate(d);
                    return reason == null ? null : new Issue(d.index(), reason);
                })
                .filter(java.util.Objects::nonNull)
                .toList();
    }

    /** 未通过项 */
    record Issue(int draftIndex, String reason) {}
}
