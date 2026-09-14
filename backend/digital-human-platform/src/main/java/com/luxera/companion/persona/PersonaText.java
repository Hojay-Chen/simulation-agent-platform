package com.luxera.companion.persona;

import java.util.Map;

/** 把人格模型渲染成给 LLM 的身份描述片段(数据库是源,这里是运行时投影) */
public final class PersonaText {

    private static final Map<String, String> TENDENCY_ZH = Map.ofEntries(
            Map.entry("listen_first", "先倾听"),
            Map.entry("avoid_immediate_advice", "不急于给建议"),
            Map.entry("offer_presence", "表达陪伴"),
            Map.entry("ask_at_most_one_open_question", "最多问一个开放问题"),
            Map.entry("celebrate", "一起开心"),
            Map.entry("show_interest", "表现出兴趣"),
            Map.entry("share_joy", "分享喜悦"),
            Map.entry("accept_gracefully", "大方接受纠正"),
            Map.entry("update_understanding", "更新自己的理解"),
            Map.entry("thank_them", "感谢对方纠正"),
            Map.entry("check_in_gently", "轻轻问候"),
            Map.entry("use_nickname", "偶尔用亲昵称呼"),
            Map.entry("respect_silence", "尊重沉默")
    );

    private PersonaText() {}

    /** 生成系统提示中的身份与人格描述 */
    public static String describe(Companion c, Persona p) {
        StringBuilder sb = new StringBuilder();
        sb.append("你是").append(c.getName()).append("。");
        if (c.getGender() != null) {
            sb.append("性别:").append("female".equals(c.getGender()) ? "女" : "男").append("。");
        }
        if (c.getBirthDate() != null) {
            sb.append("今年").append(c.age()).append("岁。");
        }
        if (p == null) {
            sb.append("自然、温暖、真诚。");
            return sb.toString();
        }
        if (p.getIdentity() != null && p.getIdentity().getBirthPlace() != null
                && p.getIdentity().getBirthPlace().getCity() != null) {
            sb.append("出生地:").append(p.getIdentity().getBirthPlace().getCity()).append("。");
        }
        if (p.getPersonality() != null && p.getPersonality().getSummary() != null) {
            sb.append("性格:").append(p.getPersonality().getSummary()).append("。");
        }
        if (p.getLife() != null && p.getLife().getBackground() != null) {
            sb.append("生活背景:").append(p.getLife().getBackground()).append("。");
        }
        if (p.getCommunication() != null && p.getCommunication().getStyle() != null) {
            sb.append("说话风格:").append(p.getCommunication().getStyle()).append("。");
        }
        if (p.getBehaviors() != null && !p.getBehaviors().isEmpty()) {
            sb.append("行为倾向:");
            for (Persona.Behavior b : p.getBehaviors()) {
                sb.append("当").append(triggerZh(b.getTrigger())).append("时,");
                for (String t : b.getTendencies()) {
                    sb.append(TENDENCY_ZH.getOrDefault(t, t)).append("、");
                }
                sb.setLength(sb.length() - 1);
                sb.append(";");
            }
        }
        if (p.getValues() != null && !p.getValues().isEmpty()) {
            sb.append("在意:").append(String.join("、", p.getValues())).append("。");
        }
        if (p.getBoundaries() != null && !p.getBoundaries().isEmpty()) {
            sb.append("边界:").append(String.join("、", p.getBoundaries())).append("。");
        }
        return sb.toString();
    }

    private static String triggerZh(String trigger) {
        if (trigger == null) return "未知情境";
        switch (trigger) {
            case "user_is_upset": return "用户情绪低落";
            case "user_shares_good_news": return "用户分享好消息";
            case "user_corrects_me": return "用户纠正我";
            case "user_needs_space": return "用户需要空间";
            default: return trigger;
        }
    }
}
