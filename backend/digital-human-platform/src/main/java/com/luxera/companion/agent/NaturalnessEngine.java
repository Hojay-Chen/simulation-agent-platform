package com.luxera.companion.agent;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 自然度引擎: 检测并修复 AI 腔 / 模板化表达 / 说教 / 过度道歉。
 * (设计文档 79-80 节)
 */
@Component
public class NaturalnessEngine {

    private static final List<String> AI_PHRASES = List.of(
            "作为AI", "作为一个AI", "作为一个人工智能", "作为智能助手", "作为语言模型",
            "我是AI", "我的训练数据", "在我的训练数据中", "作为助手", "作为一个助手",
            "我没有情感", "我不能感受", "请记住我是一个", "我是一个人工智能",
            "我理解你的感受", "我完全理解你的感受", "我明白你的感受"
    );

    private static final List<String> TEMPLATE_COMFORT = List.of(
            "一切都会好起来的", "一切都会好的", "会好起来的", "别想太多",
            "放轻松", "不要给自己太大压力", "你已经很努力了", "你真的很优秀"
    );

    private static final List<String> OVER_ADVICE = List.of(
            "首先你要", "你应该要", "建议你每天", "记住,健康", "成年人应该",
            "你要学会", "你需要改变", "你应该早点睡", "我建议你"
    );

    private static final Pattern EMOJI_PATTERN = Pattern.compile(
            "[\\uD83C\\uDF00-\\uD83D\\uDE4F\\uD83D\\uDE80-\\uD83D\\uDEFF\\uD83E\\uDD00-\\uD83E\\uDDFF]");

    private static final Pattern APOLOGY_PATTERN = Pattern.compile("(对不起|抱歉|不好意思|我很抱歉)");

    /** 校验结果: 修复后的文本 + 问题列表 */
    public Result validate(String text) {
        List<String> issues = new ArrayList<>();
        String cleaned = text == null ? "" : text;

        for (String phrase : AI_PHRASES) {
            if (cleaned.contains(phrase)) {
                issues.add("AI套话: " + phrase);
                cleaned = cleaned.replace(phrase, "");
            }
        }
        cleaned = cleaned.replaceAll("(?i)as an AI", "").replaceAll("(?i)as an AI assistant", "");

        for (String phrase : TEMPLATE_COMFORT) {
            if (cleaned.contains(phrase)) {
                issues.add("模板安慰: " + phrase);
            }
        }

        for (String phrase : OVER_ADVICE) {
            if (cleaned.contains(phrase)) {
                issues.add("说教式建议: " + phrase);
            }
        }

        Matcher apology = APOLOGY_PATTERN.matcher(cleaned);
        int apologyCount = 0;
        while (apology.find()) apologyCount++;
        if (apologyCount >= 2) {
            issues.add("过度道歉: " + apologyCount + " 次");
        }

        Matcher emojiMatcher = EMOJI_PATTERN.matcher(cleaned);
        int emoji = 0;
        while (emojiMatcher.find()) emoji++;
        if (emoji > 3) {
            issues.add("过度emoji: " + emoji + " 个");
        }

        if (cleaned.trim().length() > 500) {
            issues.add("回应过长(" + cleaned.trim().length() + " 字)");
        }

        cleaned = cleaned.replaceAll("[\\n]{3,}", "\n\n").trim();
        return new Result(cleaned, issues);
    }

    /** 带回复预算的校验 —— 超出本回合篇幅是问题(只记录, 不截断, 由 Prompt 预算约束) */
    public Result validate(String text, com.luxera.companion.interaction.ResponseBudget budget) {
        Result r = validate(text);
        if (budget != null && text != null && text.trim().length() > budget.maxCharacters) {
            r.issues().add("超出本回合预算(" + text.trim().length() + ">" + budget.maxCharacters + "字)");
        }
        return r;
    }

    public record Result(String cleaned, List<String> issues) {}
}
