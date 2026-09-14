package com.luxera.companion.digitalhuman.conversation;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.regex.Pattern;

/**
 * NarrationRuleValidator: 旁白/舞台动作/AI 腔规则验证器(V10 §15.3 Rule Validator)。
 *
 * 真人聊天不会出现:
 * - 括号舞台动作: （放下笔笑了笑）(笑) (叹气)…
 * - Markdown 动作: *smiles* _whispers_…
 * - 旁白前缀: 她想了想、沉默片刻后、她微微一笑…
 * - AI 腔: 作为AI/我是人工智能/我不能/作为数字人…
 * - 生硬的列表式排比等机械信号(规则版)。
 *
 * 全部规则可配置(静态表 + 正则), 失败返回具体原因供重新生成。
 */
@Component
public class NarrationRuleValidator implements ConversationOutputValidator {

    /** 括号舞台动作(中英文括号) */
    private static final Pattern PAREN_ACTION = Pattern.compile(
            "[（(][^（()）)]{1,24}[)）]");

    /** Markdown 强调包夹的动作 */
    private static final Pattern MARKDOWN_ACTION = Pattern.compile(
            "(?s)\\*{1,2}[^*]{1,40}\\*{1,2}|_{1,2}[^_]{1,40}_{1,2}|~{1,2}[^~]{1,40}~{1,2}");

    /** 旁白前缀(她/我 + 动作) */
    private static final List<String> NARRATION_PREFIXES = List.of(
            "她想了想", "她微微一笑", "她笑着说", "她沉默片刻", "她犹豫了一下",
            "她叹了口气", "她抬起头", "她放下手机", "她看着我", "她轻声说",
            "沉默片刻后", "想了一下", "思考片刻", "停顿了一下", "我笑了笑",
            "笑了一下", "轻声说道", "缓缓说道", "点了点头");

    /** AI 腔(含空格/全角逗号变体) */
    private static final List<String> AI_TELLS = List.of(
            "作为AI", "作为一个人工智能", "我是一个AI", "我是人工智能",
            "作为数字人", "我不能够", "我很抱歉但我", "抱歉,我无法", "抱歉我无法",
            "抱歉, 我无法", "抱歉，我无法", "抱歉，无法",
            "作为一个语言模型", "作为语言模型", "我只是一段程序", "我没有情感", "我不具备");

    @Override
    public String validate(ChatMessageDraft draft) {
        if (draft == null || draft.text() == null || draft.text().isBlank()) {
            return "消息为空";
        }
        String text = draft.text().trim();

        if (PAREN_ACTION.matcher(text).find()) {
            return "包含括号舞台动作, 真人不会这样说话: " + text;
        }
        if (MARKDOWN_ACTION.matcher(text).find()) {
            return "包含 Markdown 动作标记, 真人不会这样说话: " + text;
        }
        for (String prefix : NARRATION_PREFIXES) {
            if (text.contains(prefix)) {
                return "包含旁白/内心描写「" + prefix + "」, 真人不会这样说话";
            }
        }
        for (String tell : AI_TELLS) {
            if (text.contains(tell)) {
                return "包含 AI 腔「" + tell + "」";
            }
        }
        return null;
    }
}
