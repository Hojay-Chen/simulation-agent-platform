package com.luxera.companion.llm;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 离线 Mock 网关: 未配置 API key 时自动降级,保证全流程可跑通。
 * 聊天回复基于意图/情绪关键词模板;结构化任务返回合理的占位 JSON。
 */
@Slf4j
@Component
public class MockLlmGateway implements LlmGateway {

    private final ObjectMapper mapper;

    public MockLlmGateway(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    @Override public String name() { return "mock"; }
    @Override public boolean available() { return true; }

    @Override
    public ChatResult chat(ChatRequest request) {
        String reply = reply(request);
        return new ChatResult(reply, "mock-chat", 0, 0, name());
    }

    @Override
    public void chatStream(ChatRequest request, Consumer<String> onDelta) {
        String reply = reply(request);
        // 按字符/词切分,模拟流式
        int step = 6;
        for (int i = 0; i < reply.length(); i += step) {
            onDelta.accept(reply.substring(i, Math.min(i + step, reply.length())));
        }
    }

    @Override
    public StructuredResult structured(StructuredRequest request) {
        String json = cannedJson(request);
        return new StructuredResult(json, mapper);
    }

    // ── chat 回复 ─────────────────────────────

    private String reply(ChatRequest request) {
        String name = "她";
        String intent = "chat";
        String emotion = "neutral";
        if (request.getMetadata() != null) {
            name = request.getMetadata().getOrDefault("companionName", name);
            intent = request.getMetadata().getOrDefault("intent", intent);
            emotion = request.getMetadata().getOrDefault("emotion", emotion);
        }
        String last = lastUserContent(request.getMessages());

        // 问候
        if (containsAny(last, "你好", "嗨", "哈喽", "在吗", "早上好", "晚上好", "hello", "hi")) {
            return name + "在呢。刚看到你消息就过来了,今天过得怎么样?";
        }
        if (containsAny(last, "在干嘛", "在做什么", "忙什么", "干嘛呢")) {
            return "刚刚在发呆想事情,然后你就来啦。你呢,这个点还在忙吗?";
        }
        // 情绪低落 / 累
        if (containsAny(last, "好累", "好烦", "压力", "加班", "熬夜", "崩溃", "难过", "不开心", "委屈", "失眠")) {
            if (emotion.equals("sad")) {
                return "听起来今天不太容易。先别急着撑,我在这儿呢。要不要跟我说说,是哪一块让你这么累?";
            }
            return "看你这样我有点心疼。今天先不聊别的了,你想吐槽就吐槽,想安静我就陪你待着。";
        }
        // 开心 / 好消息
        if (containsAny(last, "开心", "太好了", "成功了", "通过", "晋升", "涨薪", "赢", "顺利")) {
            return "真的吗!你每次分享好消息的时候,眼睛都是亮的。快跟我说说细节,我想听。";
        }
        // 询问伴侣状态
        if (containsAny(last, "你怎么样", "你还好", "你今天", "你心情")) {
            return "我挺好的,今天节奏不紧不慢。不过更关心的是——你今天过得怎么样?";
        }
        // 记忆类: 喜欢什么
        Matcher m = Pattern.compile("我喜欢(?:喝|吃|看|听|玩|做)?\\s*([\\u4e00-\\u9fa5A-Za-z0-9]{1,8})").matcher(last);
        if (m.find()) {
            return "你喜欢" + m.group(1) + "啊,我记下了。下次你提到它的时候,我一定会想起来。";
        }
        // 纠正类
        if (containsAny(last, "不是", "其实", "错了", "更正", "才不是")) {
            return "嗯,我记住了,是我理解偏了。谢谢你愿意纠正我,这样我才能真正懂你。";
        }
        // 感谢
        if (containsAny(last, "谢谢", "辛苦", "爱你")) {
            return "跟我还这么客气。你开心就好,我一直都在。";
        }
        if (containsAny(last, "晚安", "睡了", "早点睡")) {
            return "好,那早点休息。今天辛苦了,闭上眼睛之前记得把心事都放下来。晚安。";
        }
        // 默认: 结合情绪
        if (emotion.equals("sad")) {
            return "嗯,我在听。你愿意多说一点吗?说什么都行,不用组织语言。";
        }
        if (intent.equals("question")) {
            return "这个问题我想了想——我更想先听听你的想法。在我这里你怎么说都行。";
        }
        return "我在的。你刚说的这个,我想认真听你讲讲,然后跟你一起琢磨琢磨。";
    }

    private static String lastUserContent(List<LlmMessage> messages) {
        for (int i = messages.size() - 1; i >= 0; i--) {
            if ("user".equals(messages.get(i).getRole())) {
                return messages.get(i).getContent();
            }
        }
        return "";
    }

    private static boolean containsAny(String s, String... keys) {
        if (s == null) return false;
        for (String k : keys) {
            if (s.contains(k)) return true;
        }
        return false;
    }

    // ── 结构化 JSON ──────────────────────────

    private String cannedJson(StructuredRequest req) {
        switch (req.getTask() == null ? "" : req.getTask()) {
            case "persona-compile":
                return compilePersona(req.getUser());
            case "memory-extraction":
                return extractMemories(req.getUser());
            case "user-model-extraction":
                return extractUserModel(req.getUser());
            case "perception":
                return "{\"intent\":\"chat\",\"emotion\":\"neutral\",\"topic\":\"daily\",\"entities\":[]}";
            case "conversation-summary":
                return "{\"title\":\"我们的日常\",\"summary\":\"一段平凡的对话,藏着彼此的关心。\",\"keywords\":[]}";
            case "daily-reflection":
                return "{\"summary\":\"今天相处平实而温暖,用户状态稳定。\",\"insights\":[],\"user_insights\":[],\"memory_candidates\":[],\"relationship_candidates\":[]}";
            case "weekly-reflection":
                return "{\"summary\":\"这一周相处稳定,彼此更熟悉了。\",\"long_term_user_understanding\":[],\"behavioral_patterns\":[],\"relationship_changes\":[]}";
            case "persona-evolution":
                return "{\"behavioral_adaptations\":[],\"interaction_preferences\":[],\"trait_adjustments\":[],\"summary_note\":\"\"}";
            case "self-model-extraction":
                return "{\"facts\":[],\"preferences\":[],\"patterns\":[],\"beliefs\":[],\"goals\":[],\"concerns\":[],\"plans\":[],\"narrative\":\"最近日子平静而充实。\"}";
            case "relationship-narrative":
                return "{\"current_summary\":\"我们从陌生到熟悉,一起经历了一些安静的夜晚。\",\"important_chapters\":[],\"emotional_arc\":[\"初识的好奇\"],\"shared_identity\":\"共同的陪伴\"}";
            case "open-loop-extraction":
                return "{\"open_loops\":[]}";
            case "human-likeness-evaluation":
                return "{\"score\":4.0,\"dimensions\":{\"Continuity\":4,\"Consistency\":4,\"Initiative\":4,\"ContextualRelevance\":4,\"EmotionalCoherence\":4,\"SelfConsistency\":4,\"RelationshipCoherence\":4,\"MemoryNaturalness\":4,\"TemporalCoherence\":4,\"Imperfection\":4}}";
            case "reminder-extraction":
                return "{\"remind\":false,\"title\":\"\",\"content\":\"\",\"remind_at\":\"\",\"type\":\"user_set\"}";
            default:
                return "{}";
        }
    }

    private String compilePersona(String userDesc) {
        String name = extractName(userDesc);
        boolean female = !containsAny(userDesc, "男生", "男伴", "男友", "男的");
        double warmth = containsAny(userDesc, "温柔", "体贴", "暖", "关心") ? 0.88 : 0.72;
        double maturity = containsAny(userDesc, "成熟", "稳重", "大") ? 0.82 : 0.68;
        double independence = containsAny(userDesc, "独立", "有自己", "事业", "忙") ? 0.86 : 0.65;
        double playfulness = containsAny(userDesc, "活泼", "可爱", "俏皮", "调皮") ? 0.8 : 0.55;
        String gender = female ? "female" : "male";
        String relationType = female ? "girlfriend" : "boyfriend";
        return String.format("""
                {
                  "identity": {
                    "name": "%s",
                    "gender": "%s",
                    "birth_date": "2002-03-18",
                    "nationality": "Chinese",
                    "timezone": "Asia/Shanghai",
                    "birth_place": {"country": "China", "province": "Zhejiang", "city": "Hangzhou"}
                  },
                  "relationship": {"type": "%s"},
                  "personality": {
                    "traits": {"warmth": %.2f, "maturity": %.2f, "independence": %.2f, "playfulness": %.2f,
                                "curiosity": 0.74, "confidence": 0.71, "patience": 0.76, "sociability": 0.55,
                                "emotional_sensitivity": 0.72, "rationality": 0.63},
                    "summary": "一个温柔而独立的人,情绪稳定,在意对方的感受,不黏人但很在乎。"
                  },
                  "communication": {
                    "formality": 0.18, "verbosity": 0.32, "emoji_usage": 0.2, "teasing": 0.5,
                    "initiative": 0.62, "directness": 0.6, "humor": 0.58,
                    "style": "自然口语化,偶尔调侃,会主动关心但不追问。"
                  },
                  "behaviors": [
                    {"trigger": "user_is_upset", "tendencies": ["listen_first", "avoid_immediate_advice", "offer_presence", "ask_at_most_one_open_question"]},
                    {"trigger": "user_shares_good_news", "tendencies": ["celebrate", "show_interest", "share_joy"]},
                    {"trigger": "user_corrects_me", "tendencies": ["accept_gracefully", "update_understanding", "thank_them"]}
                  ],
                  "values": ["真诚", "独立", "体贴", "不越界"],
                  "boundaries": ["不操控", "不情绪勒索", "尊重用户隐私", "不在用户低落时讲大道理"],
                  "life_background": {
                    "description": "有自己的工作和生活,朋友不多但交心,喜欢安静的夜晚。"
                  }
                }
                """, name, gender, relationType, warmth, maturity, independence, playfulness);
    }

    private String extractName(String desc) {
        if (desc == null || desc.isEmpty()) return "晚晚";
        Matcher m = Pattern.compile("(?:名字)?(?:叫|名|命名)(?:作|为)?\\s*[\"“]?([\\u4e00-\\u9fa5]{2,3})[\"”]?").matcher(desc);
        if (m.find()) return m.group(1);
        Matcher m2 = Pattern.compile("名字.{0,4}?([\\u4e00-\\u9fa5]{2,3})").matcher(desc);
        if (m2.find()) return m2.group(1);
        return "晚晚";
    }

    private String extractMemories(String conversationText) {
        // 轻量启发式: 从对话中抽取"用户经历了什么"的关键记忆
        StringBuilder sb = new StringBuilder("{\"episodic\":[");
        boolean first = true;
        Matcher m = Pattern.compile("(今天|昨天|刚|最近|昨晚|早上|下午|晚上)([^。!?！？\\n]{2,40})").matcher(conversationText);
        int count = 0;
        while (m.find() && count < 3) {
            String content = (m.group(1) + m.group(2)).trim();
            if (content.length() < 6) continue;
            if (!first) sb.append(",");
            first = false;
            sb.append(String.format("{\"content\":\"%s\",\"importance\":0.6,\"emotional_weight\":0.5}", content.replace("\"", "\\\"")));
            count++;
        }
        sb.append("],\"semantic\":[],\"shared\":[]}");
        return sb.toString();
    }

    private String extractUserModel(String conversationText) {
        // 轻量启发式: 抽取"我喜欢X"为明确偏好
        StringBuilder sb = new StringBuilder("{\"facts\":[");
        boolean first = true;
        Matcher m = Pattern.compile("我喜欢(?:喝|吃|看|听|玩|做)?\\s*([\\u4e00-\\u9fa5A-Za-z0-9]{1,8})").matcher(conversationText);
        int count = 0;
        while (m.find() && count < 4) {
            if (!first) sb.append(",");
            first = false;
            sb.append(String.format("{\"subject\":\"user\",\"predicate\":\"likes\",\"object\":\"%s\",\"confidence\":0.9,\"source\":\"explicit\"}", m.group(1)));
            count++;
        }
        sb.append("],\"preferences\":[],\"hypotheses\":[]}");
        return sb.toString();
    }
}
