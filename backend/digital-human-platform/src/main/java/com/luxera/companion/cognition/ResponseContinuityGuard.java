package com.luxera.companion.cognition;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * V11 §13.2 —— <b>"别把同一句话再说一遍"</b>。
 *
 * <h2>它治的是哪一种病</h2>
 * 不是"回复质量不高", 是<b>重复</b>。而且重复在 LLM 生成的对话里有一个很具体的形状:
 * <pre>
 *   用户: 我今天面试挂了
 *   她:   啊……别难过, 抱抱你
 *   用户: 准备了三个月
 *   她:   啊……别难过, 抱抱你     ← 同一句话
 *
 *   她:   你今天吃饭了吗
 *   (两小时后)
 *   她:   你今天吃饭了吗          ← 同一个问题, 问了两次
 * </pre>
 * 这两种在真人身上几乎不会发生, 而它们<b>不需要读完整段对话</b>就能检测 —— 只要看
 * 最近几条她说过的话。所以本类是一个纯字符串比较器, 不碰 LLM, 不碰数据库。
 *
 * <h2>为什么不做成"语义去重"</h2>
 * 因为语义去重需要一个模型, 而一个模型判"这两句是不是一个意思"会在<b>真正该重复</b>的时候
 * 误判: "我在" / "我在呢" / "我在的" 语义相同, 而人就是会这样说。
 * 本类只抓三件有确定答案的事(逐字重复 / 同一个问题 / 连着两次同一个语气词),
 * 剩下的交给 Composer 的提示词。**宁可漏判, 不可误判** —— 误判的后果是她该说话时被堵住嘴,
 * 而那种故障不会报错, 只会表现为"她最近话变少了"。
 *
 * <h2>为什么判定结果是一个 record 而不是 boolean</h2>
 * 因为四种情况要做的处理不一样: 逐字重复 → 重新生成; 问过的问题 → 换一种问法;
 * 重复语气词 → 大概率无所谓(放行)。把它们压成一个 {@code true/false} 会让调用方
 * 要么全拦要么全放, 而"全拦"会把正常对话也拦掉。
 */
@Component
public class ResponseContinuityGuard {

    /** 只看最近这么多条她自己的消息。看太多会把"她一贯的口头禅"当成重复。 */
    static final int LOOKBACK = 6;

    /** 少数几个"连着说两次就不像人"的语气词。刻意只有这几个 —— 收录越多, 误判越多。 */
    private static final List<String> INTERJECTIONS =
            List.of("哈哈", "嘿嘿", "呵呵", "嗯嗯", "诶", "哎呀", "唉");

    /**
     * "短回复"的长度上限(归一化之后)。
     *
     * <p>10 这个数字是<b>故意的、可以调的</b>: 它决定"多长的一句话就不算'只有语气词'了"。
     * 调大 → 更容易判成重复(误判风险上升); 调小 → 连着的两个"哈哈"抓不到。
     * 之所以需要它, 见 {@link #check} 第 3 条的说明。
     */
    static final int SHORT_REPLY = 10;

    /**
     * @param verdict  结论
     * @param detail   人类可读的说明(进日志, 不进用户消息)
     * @param evidence 撞上的那条旧消息 —— 排查时需要看到"到底和哪一句重了"
     */
    public record Verdict(Kind kind, String detail, String evidence) {

        public enum Kind {
            /** 没有发现问题 */
            OK,
            /** 逐字重复(归一化之后完全一样) */
            DUPLICATE,
            /** 问过的问题又问了一遍 */
            REPEATED_QUESTION,
            /** 承接生硬: 与前一条开场语气词相同 */
            REPEATED_OPENING
        }

        public boolean isProblem() {
            return kind == Kind.DUPLICATE || kind == Kind.REPEATED_QUESTION;
        }

        static Verdict ok() {
            return new Verdict(Kind.OK, "no_repetition", null);
        }
    }

    /**
     * 检查一条待发送的回复。
     *
     * @param candidate 她准备发的话
     * @param recentCompanionMessages 她最近说过的话, <b>必须按时间升序</b>(旧 → 新),
     *                                与 {@code ChatWorldPort.recentMessages} 的契约一致。
     *                                顺序不是可有可无的: "紧接着的上一条"是最后那个元素,
     *                                而这个判断决定了"连着两次同一个语气词"能不能被抓到。
     *                                null / 空 = 无历史可查
     */
    public Verdict check(String candidate, List<String> recentCompanionMessages) {
        String c = normalize(candidate);
        if (c.isEmpty()) {
            // 空回复不是重复问题, 是"她什么都没说" —— 那是别的闸门(输出验证)管的
            return Verdict.ok();
        }
        List<String> recent = tail(recentCompanionMessages);
        if (recent.isEmpty()) {
            return Verdict.ok();
        }

        // 1. 逐字重复 —— 最硬的一条, 归一化之后完全一样就是重复
        for (String old : recent) {
            if (c.equals(normalize(old))) {
                return new Verdict(Verdict.Kind.DUPLICATE, "identical_to_recent_reply", old);
            }
        }

        // 2. 问过的问题又问一遍。只对"候选是问句"生效 —— 反过来(旧的是问句、新的不是)
        //    是正常的: 她问完自己又补了一句陈述。
        //    这里比的是<b>问句主干</b>而不是原句: "你今天吃饭了吗" 与 "你今天吃饭了没"
        //    是同一个问题换了语气词, 真人不觉得这是两句话 —— 而逐字比较抓不到它。
        //    主干短于 4 个字就不比(「在吗」这种太短, 撞上的概率高得没有意义)。
        if (isQuestion(candidate)) {
            String stem = questionStem(c);
            if (stem.length() >= 4) {
                for (String old : recent) {
                    if (isQuestion(old) && stem.equals(questionStem(normalize(old)))) {
                        return new Verdict(Verdict.Kind.REPEATED_QUESTION, "same_question_asked_again", old);
                    }
                }
            }
        }

        // 3. 连着两次短回复用同一个语气词开头。两个限定词都不能去掉:
        //    "紧接着的上一条" —— "哈哈"隔三条出现一次是她的说话习惯, 连着两次才是卡住了;
        //    "短回复"        —— "诶今天天气不错" 接 "诶你说的那个我想起来了…" 完全正常,
        //                      真正常见的坏样子是连着两条都只有"哈哈"/"嗯嗯"这种长度。
        String opening = openingInterjection(c);
        if (opening != null && c.length() <= SHORT_REPLY) {
            String prev = recent.get(recent.size() - 1);   // 契约: 升序, 最后一个是"上一条"
            String prevNormalized = normalize(prev);
            if (prevNormalized.length() <= SHORT_REPLY && opening.equals(openingInterjection(prevNormalized))) {
                return new Verdict(Verdict.Kind.REPEATED_OPENING, "same_opening_interjection", prev);
            }
        }
        return Verdict.ok();
    }

    /**
     * 给重新生成用的提示词片段。
     *
     * <p>用中文写, 因为它是喂给 Composer 的 —— 而 Composer 的指令本来就是中文。
     * 返回 null 表示"没什么要提示的"(OK 的情况), 调用方据此决定要不要二次生成。
     */
    public String rewriteHint(Verdict verdict) {
        if (verdict == null || verdict.kind() == Verdict.Kind.OK) {
            return null;
        }
        return switch (verdict.kind()) {
            case DUPLICATE -> "你刚才已经说过意思几乎一样的话了(「"
                    + ellipsis(verdict.evidence()) + "」), 换一个说法, 或者干脆只做一个动作/一句很短的反应";
            case REPEATED_QUESTION -> "你已经问过这个问题了(「"
                    + ellipsis(verdict.evidence()) + "」), 不要重复问, 换成回应对方刚说的内容";
            case REPEATED_OPENING -> "不要又用「" + openingInterjection(normalize(verdict.evidence()))
                    + "」开头, 换一个开场";
            case OK -> null;
        };
    }

    // ─────────────────────────── 纯函数部分 ───────────────────────────

    /** 归一化: 去掉所有空白与标点, 只留字。这样"别难过, 抱抱你" 与 "别难过 抱抱你。" 会撞上。 */
    static String normalize(String s) {
        if (s == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (Character.isWhitespace(ch) || Character.isSpaceChar(ch)) {
                continue;
            }
            if (isPunctuation(ch)) {
                continue;
            }
            sb.append(ch);
        }
        return sb.toString();
    }

    /**
     * 标点判定用<b>显式列举</b>而不是 {@code Character.getType} —— 因为后者对
     * 全角标点("。" "，" "？")返回的是 {@code OTHER_PUNCTUATION}, 而对 emoji 返回的是
     * {@code OTHER_SYMBOL}, 两者差别微妙且随 JDK 版本变。显式列举可以一眼看全, 也不会
     * 因为升级 JDK 而改变行为。
     */
    private static boolean isPunctuation(char ch) {
        return "，。！？、；：,.!?;:\"'“”‘’（）()《》〈〉【】[]…—～~-·`".indexOf(ch) >= 0;
    }

    private static boolean isQuestion(String s) {
        return s != null && (s.contains("？") || s.contains("?"));
    }

    /**
     * 问句主干: 去掉句末的语气助词, 于是"你今天吃饭了吗" 与 "你今天吃饭了没" 会撞上。
     *
     * <p>只削<b>句末</b>的助词, 不动句子中间 —— "你吃了吗" 与 "你吃了吗" 的差别全在结尾,
     * 而中间挖空会把"你想我了吗"和"你想他了吗"变成同一句。
     */
    static String questionStem(String normalized) {
        String s = normalized;
        // 先削多字组合, 再削单字 —— 顺序反了会把"了没"削成"了"再削成"", 留下一个错误的空主干
        for (String tail : List.of("了没有", "了没", "没有", "了吗", "了么", "吗", "呢", "吧", "么")) {
            if (s.length() > tail.length() && s.endsWith(tail)) {
                s = s.substring(0, s.length() - tail.length());
                break;
            }
        }
        return s;
    }

    /** 开场的语气词。只看开头 —— 句子中间出现的"哈哈"不算。 */
    static String openingInterjection(String normalized) {
        for (String w : INTERJECTIONS) {
            if (normalized.startsWith(w)) {
                return w;
            }
        }
        return null;
    }

    private static List<String> tail(List<String> messages) {
        if (messages == null || messages.isEmpty()) {
            return List.of();
        }
        List<String> cleaned = new ArrayList<>();
        for (String m : messages) {
            if (m != null && !m.isBlank()) {
                cleaned.add(m);
            }
        }
        if (cleaned.size() <= LOOKBACK) {
            return cleaned;
        }
        return cleaned.subList(cleaned.size() - LOOKBACK, cleaned.size());
    }

    private static String ellipsis(String s) {
        if (s == null) {
            return "";
        }
        return s.length() <= 16 ? s : s.substring(0, 16) + "…";
    }

    /** 供测试与诊断用: 返回被认定为重复的那批归一化文本。 */
    Set<String> normalizedSet(List<String> messages) {
        Set<String> out = new LinkedHashSet<>();
        for (String m : tail(messages)) {
            out.add(normalize(m));
        }
        return out;
    }
}
