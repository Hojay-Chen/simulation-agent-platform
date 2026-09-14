package com.luxera.companion.agent;

import org.springframework.stereotype.Component;

/**
 * 感知引擎: 实时用启发式快速识别意图/情绪/话题(不阻塞回复),
 * 精度由异步 {@link PerceptionRefiner} 用 LLM 精炼补齐。
 * (设计文档 39 节)
 */
@Component
public class PerceptionEngine {

    public Perception perceive(String text) {
        if (text == null) text = "";
        return new Perception(intent(text), emotion(text), topic(text));
    }

    private String intent(String t) {
        if (containsAny(t, "你好", "嗨", "哈喽", "在吗", "hi", "hello", "早上好", "晚上好", "早安", "嗨喽", "好久不见")) return "greeting";
        if (containsAny(t, "不是", "其实", "错了", "更正", "才不是", "不对", "我没说", "你记错了")) return "correction";
        if (containsAny(t, "晚安", "睡了", "要睡了", "先休息", "早点睡", "睡觉了")) return "say_goodnight";
        if (containsAny(t, "再见", "拜拜", "回聊", "先忙了", "不聊了")) return "farewell";
        if (containsAny(t, "谢谢", "辛苦", "爱你", "有你在", "多亏")) return "gratitude";
        if (containsAny(t, "提醒", "记得", "帮我", "安排", "日程", "日历", "闹钟", "记一下", "别忘了")) return "request_tool";
        if (containsAny(t, "你怎么样", "你还好", "你今天", "你心情", "你在干嘛", "你想我吗", "你喜欢我吗", "你最近")) return "ask_about_her";
        if (containsAny(t, "真漂亮", "真好", "你真好", "你好可爱", "你好厉害", "真棒", "好看")) return "compliment";
        if (containsAny(t, "？", "?", "吗", "为什么", "怎么", "能不能", "可不可以", "是不是", "什么", "几点", "谁", "去哪", "哪些")) return "question";
        if (containsAny(t, "好累", "累死", "加班", "熬夜", "没睡好", "腰疼", "头疼", "感冒")) return "share_tired";
        if (containsAny(t, "好烦", "压力", "崩溃", "难过", "不开心", "委屈", "失眠", "焦虑", "好难", "emo", "破防", "想哭")) return "share_upset";
        if (containsAny(t, "开心", "太好了", "成功", "通过", "晋升", "涨薪", "赢了", "高兴", "兴奋", "好消息", "哈哈")) return "share_joy";
        if (containsAny(t, "周末", "改天", "一起", "约", "计划", "打算", "安排一下", "约着")) return "planning";
        return "chat";
    }

    private String emotion(String t) {
        if (containsAny(t, "好累", "累死", "加班", "失眠", "熬夜", "没睡好", "腰酸")) return "tired";
        if (containsAny(t, "难过", "崩溃", "委屈", "破防", "哭", "伤心", "想哭", "心碎")) return "sad";
        if (containsAny(t, "焦虑", "压力", "紧张", "害怕", "担心", "不安")) return "anxious";
        if (containsAny(t, "生气", "气死", "烦死", "讨厌", "无语", "受不了", "火大")) return "angry";
        if (containsAny(t, "开心", "太好了", "兴奋", "高兴", "哈哈", "嘿嘿", "超棒", "激动")) return "happy";
        if (containsAny(t, "孤单", "寂寞", "想你了", "一个人", "没人陪")) return "lonely";
        if (containsAny(t, "谢谢", "感动", "温暖", "有你真好")) return "grateful";
        return "neutral";
    }

    private String topic(String t) {
        if (containsAny(t, "工作", "上班", "同事", "老板", "项目", "加班", "开会", "出差", "辞职", "面试", "KPI", "业绩")) return "work";
        if (containsAny(t, "学习", "考试", "论文", "上课", "考研", "作业", "复习", "学校", "课程")) return "study";
        if (containsAny(t, "睡", "吃", "身体", "感冒", "头疼", "疼", "累", "锻炼", "跑步", "健身", "健康", "减肥")) return "health";
        if (containsAny(t, "你", "我们", "关系", "喜欢", "想念", "想你", "见面", "恋爱", "感情")) return "relationship";
        if (containsAny(t, "电影", "剧", "番", "音乐", "歌", "书", "游戏", "小说")) return "entertainment";
        if (containsAny(t, "美食", "吃", "火锅", "奶茶", "咖啡", "餐厅", "做饭")) return "food";
        if (containsAny(t, "旅行", "旅游", "景点", "海边", "西湖", "周末去", "机票", "酒店")) return "travel";
        if (containsAny(t, "天气", "下雨", "冷", "热", "气温")) return "weather";
        return "daily";
    }

    private static boolean containsAny(String s, String... keys) {
        for (String k : keys) {
            if (s.contains(k)) return true;
        }
        return false;
    }

    /** 感知结果 */
    public record Perception(String intent, String emotion, String topic) {}
}
