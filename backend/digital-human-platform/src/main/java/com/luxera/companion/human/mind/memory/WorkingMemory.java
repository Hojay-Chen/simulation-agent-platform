package com.luxera.companion.human.mind.memory;

import com.luxera.companion.boundary.action.ActionResult;
import com.luxera.companion.human.mind.percept.Percept;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

/**
 * V2.2 §3.4.1 / §9 验收标准 E —— <b>工作记忆: 她此刻脑子里放着的那几件事</b>。
 *
 * <h2>它为什么必须与 {@link MemoryStore} 分开, 而不是"最近 N 条长期记忆"</h2>
 * 因为这两样东西的<b>可见性规则不一样</b>, 而 §9 的验收标准 E 正是靠这个差别成立的:
 *
 * <ul>
 *   <li><b>长期记忆</b>里放的是她的<b>理解</b>("他说他要换工作了"), 不是原文, 见
 *       {@link MemoryRecord} 的禁令;</li>
 *   <li><b>工作记忆</b>是<b>唯一允许出现原文的地方</b> —— 因为要理解一句话, 就必须先
 *       把它原样拿在手上。</li>
 * </ul>
 *
 * <p>验收标准 E 说的是: <b>读消息这个动作成功之前, 原文不许出现在这里</b>。
 * 这句话要是没有落在一个具体的对象上, 它就是一句道德要求。本类把它落成了两条可执行的规则:
 *
 * <ol>
 *   <li><b>只有 {@link #admitOutcome} 能装原文</b>, 而它接受的是一个
 *       {@link ActionResult} —— 动作<b>失败</b>时它什么都不装。于是"先看见正文、
 *       再假装自己是读了才知道的"这条路在类型上不存在:
 *       Mind 里没有任何别的方法能把一段不是来自 Action 的字符串塞进来;</li>
 *   <li><b>它是易失的</b>。没有 {@code store()}、没有落库路径、容量满了就丢最旧的。
 *       一个"自动持久化"的工作记忆会让验收标准 E 变成"我们承诺不写" ——
 *       而它现在的形态是"写了也没地方去"。</li>
 * </ol>
 *
 * <h2>它不负责什么</h2>
 * <ul>
 *   <li><b>不做注意力</b>。什么东西配进工作记忆, 由 {@code AttentionService} 决定 ——
 *       本类只提供容器, 并且在容量满时按 <b>权重最低</b> 淘汰, 不做任何"该不该注意"的判断;</li>
 *   <li><b>不做归纳</b>。把这里的几段原文变成一句"他说他要换工作了"并写进长期记忆,
 *       是认知环节的事。工作记忆交出去的是 {@link #drain()} 的原始清单, 不是结论;</li>
 *   <li><b>不跨轮次保留正文</b>。{@link #drain()} 之后调用方<b>必须</b>调
 *       {@link #clear()} —— 这两步故意没有合成一个方法, 因为"读完了"与"忘了"
 *       在语义上是两件事, 而合成一个会让"她卡住了、来不及忘"这种情况无路可走。</li>
 * </ul>
 *
 * <h2>{@link EntryKind} 为什么可以是 enum</h2>
 * 用 §1.3 P4 的判据: 取值集合由<b>本设计的内部逻辑</b>决定, 还是由外部世界的多样性决定?
 * 这里是最清楚的<b>前者</b> —— 工作记忆里放什么, 完全由本设计的装配代码决定,
 * 世界上不存在"第五种工作记忆槽位"。这与 {@code Modality}(生理事实)、
 * {@link MemoryKind}(检索结构)是三条不同理由支撑的同一个结论, 刻意没有互相引用,
 * 因为"别的 enum 也是这么论证的"永远不是论证。
 */
public final class WorkingMemory {

    /** 默认容量。取 32 而不是无限: 一个记不住上限的工作记忆等于没有工作记忆。 */
    public static final int DEFAULT_CAPACITY = 32;

    private final Deque<Entry> entries = new ArrayDeque<>();
    private final int capacity;
    private final AtomicLong sequence = new AtomicLong();

    public WorkingMemory() {
        this(DEFAULT_CAPACITY);
    }

    public WorkingMemory(int capacity) {
        if (capacity < 1) {
            throw new IllegalArgumentException(
                    "工作记忆的容量至少为 1, 收到 " + capacity
                            + " —— 容量为 0 的工作记忆能装下一切(因为什么都不存), 那不是设计");
        }
        this.capacity = capacity;
    }

    // ─────────────────────────── 写入 ───────────────────────────

    /**
     * 装一段内容。这是本类唯一的原始入口, <b>但它是包内可见的</b> —— 见下面两条专用入口。
     *
     * <p>它被收紧成 package-private 是有意的: 从 {@code mind} 包外看,
     * 想往工作记忆里放东西只有几条明确的、带语义的路, 而不是一个万能 {@code put}。
     */
    Optional<Entry> admit(EntryKind kind, String content, String sourceObjectId,
                          double weight, Instant at) {
        Objects.requireNonNull(kind, "工作记忆的条目必须说明它是什么");
        Objects.requireNonNull(content, "空的条目会在工作记忆里占一个位置而不带任何信息");
        Objects.requireNonNull(at, "条目必须带时刻 —— 不许读系统时钟");
        if (content.isBlank()) {
            return Optional.empty();
        }
        double clamped = weight < 0.0 ? 0.0 : Math.min(weight, 1.0);
        Entry entry = new Entry("wm-" + sequence.incrementAndGet(), kind, content,
                sourceObjectId == null ? "" : sourceObjectId, clamped, at);
        while (entries.size() >= capacity) {
            entries.pollFirst();
        }
        entries.addLast(entry);
        return Optional.of(entry);
    }

    /**
     * 把一条她<b>注意到</b>的感知装进来。
     *
     * <p>注意传进来的是 {@link Percept} 而不是 {@code SensoryEvent}: 工作记忆里放的是
     * <b>她解释过的世界</b>, 不是世界的原始信号。直接装原始信号会让"她没有注意到"
     * 这件事在工作记忆里留下痕迹 —— 而那正是 {@code AttentionService} 存在的意义。
     */
    public Optional<Entry> admitPercept(Percept percept, Instant at) {
        Objects.requireNonNull(percept, "要装进来的感知不能为空");
        return admit(EntryKind.ATTENTION, percept.content(),
                percept.source().objectId(), percept.salience(), at);
    }

    /**
     * 把一次<b>已经成功</b>的动作的结果装进来。
     *
     * <p><b>这是验收标准 E 的落点。</b>它在动作没有生效时什么都不装 ——
     * 所以"她读到了正文"这件事, 在代码里只有一个成因: 一次真的成功了的读动作。
     *
     * <p>它不了解 {@code data} 里有什么键, 也不该了解: 一旦这里出现
     * {@code data.get("正文")} 这样的代码, 通用性就没了, 下一个 Application
     * 又要回来改这里。所以它把整个 {@code data} 渲染成一段可读文本 —— 谁放的键,
     * 谁自己负责键名的可读性。
     */
    public Optional<Entry> admitOutcome(ActionResult result, Instant at) {
        Objects.requireNonNull(result, "动作结果不能为空");
        Objects.requireNonNull(at, "动作结果必须带时刻 —— 不许读系统时钟");
        if (!result.ok()) {
            return Optional.empty();
        }
        String rendered = render(result);
        return admit(EntryKind.ACTION_RESULT, rendered, "", 0.8, at);
    }

    /** 把一段<b>回忆</b>装进来 —— 检索结果进工作记忆的那一步。 */
    public Optional<Entry> admitRecall(MemoryRecord record, Instant at) {
        Objects.requireNonNull(record, "要回忆的记录不能为空");
        return admit(EntryKind.RECALL, record.content(), "", record.importance(), at);
    }

    /** 装一个她<b>自己冒出来的念头</b> —— 它不来自任何外部刺激, 所以没有来源对象。 */
    public Entry hold(EntryKind kind, String thought, Instant at) {
        EntryKind actual = kind == EntryKind.ATTENTION || kind == EntryKind.ACTION_RESULT
                ? EntryKind.THOUGHT : kind;
        return admit(actual, thought, "", 0.5, at)
                .orElseThrow(() -> new IllegalArgumentException("念头不能是空白的"));
    }

    // ─────────────────────────── 读取 ───────────────────────────

    /** 全部条目, 最旧的在前 —— 这个次序就是"她刚才的思路"。 */
    public List<Entry> entries() {
        return List.copyOf(entries);
    }

    /** 最近 {@code n} 条, 最旧的在前。 */
    public List<Entry> recent(int n) {
        if (n <= 0) {
            return List.of();
        }
        List<Entry> all = new ArrayList<>(entries);
        return List.copyOf(all.subList(Math.max(0, all.size() - n), all.size()));
    }

    public Optional<Entry> latest() {
        return Optional.ofNullable(entries.peekLast());
    }

    /** 某一类条目 —— "她此刻正在想什么"。 */
    public List<Entry> of(EntryKind kind) {
        List<Entry> hits = new ArrayList<>();
        for (Entry e : entries) {
            if (e.kind() == kind) {
                hits.add(e);
            }
        }
        return List.copyOf(hits);
    }

    /** 按权重取最高的若干条 —— 这一轮该把什么交给认知。 */
    public List<Entry> mostWeighted(int limit) {
        if (limit <= 0) {
            return List.of();
        }
        List<Entry> sorted = new ArrayList<>(entries);
        sorted.sort(Comparator.comparingDouble(Entry::weight).reversed());
        return List.copyOf(sorted.subList(0, Math.min(limit, sorted.size())));
    }

    /**
     * 里面有这段内容吗。
     *
     * <p>这个方法存在的唯一理由是让<b>测试可以断言"正文还没进来"</b>(§9 验收标准 E)。
     * 生产代码调用它去判断"她读到了没有"是错的 —— 判断"读到没有"应当看动作结果,
     * 而不是在主文本里做子串匹配。见本方法的 {@code @Deprecated} 说明。
     *
     * @deprecated 供断言与诊断使用; 生产逻辑不应依赖内容匹配
     */
    @Deprecated
    public boolean containsContent(String needle) {
        if (needle == null || needle.isEmpty()) {
            return false;
        }
        for (Entry e : entries) {
            if (e.content().contains(needle)) {
                return true;
            }
        }
        return false;
    }

    /** 占用率 —— 越接近 1 说明她此刻越"塞满了"。它是注意力的输入之一, 由调用方取用。 */
    public double load() {
        return (double) entries.size() / capacity;
    }

    public int size() {
        return entries.size();
    }

    public int capacity() {
        return capacity;
    }

    public boolean isEmpty() {
        return entries.isEmpty();
    }

    // ─────────────────────────── 清空 ───────────────────────────

    /**
     * 把当前全部条目交出去, 并从工作记忆里移除。
     *
     * <p>它<b>不</b>调用 {@link #clear()} —— 见类注释里"为什么不合成一步"。
     */
    public List<Entry> drain() {
        List<Entry> out = new ArrayList<>(entries);
        entries.clear();
        return List.copyOf(out);
    }

    /** 明确地忘掉一条 —— 比如她意识到自己想错了。 */
    public boolean forget(String entryId) {
        return entryId != null && entries.removeIf(e -> entryId.equals(e.id()));
    }

    /** 全部忘掉 —— 一轮结束时的正常收尾, 以及回放前的复位。 */
    public void clear() {
        entries.clear();
    }

    public String describe() {
        return "WorkingMemory[" + entries.size() + "/" + capacity + " 条, 占用 "
                + Math.round(load() * 100) + "%]";
    }

    @Override
    public String toString() {
        return describe();
    }

    // ─────────────────────────── 内部 ───────────────────────────

    private static String render(ActionResult result) {
        StringBuilder sb = new StringBuilder();
        if (result.message() != null && !result.message().isBlank()) {
            sb.append(result.message());
        }
        for (var e : result.data().entrySet()) {
            if (sb.length() > 0) {
                sb.append("; ");
            }
            sb.append(e.getKey()).append('=').append(e.getValue());
        }
        if (sb.length() == 0) {
            sb.append(result.capabilityKey()).append(" 完成");
        }
        return sb.toString();
    }

    /**
     * 工作记忆里一类条目。
     *
     * <p>见类注释末尾的论证: 这个 enum 的封闭性来自"本设计的内部逻辑"。
     */
    public enum EntryKind {

        /** 她注意到的东西 —— 由注意力放进来。 */
        ATTENTION("注意到的事"),

        /** 回忆起来的往事 —— 由检索放进来。 */
        RECALL("想起的事"),

        /** 她自己冒出来的念头 —— 不来自任何刺激。 */
        THOUGHT("自己的想法"),

        /** 她此刻在追的目标 —— 装配时放进来, 让"她为什么突然提这个"有答案。 */
        GOAL("此刻的目标"),

        /** 一次成功动作的结果 —— <b>原文唯一被允许出现的地方</b>(§9 验收标准 E)。 */
        ACTION_RESULT("刚做完的事");

        private final String label;

        EntryKind(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    /**
     * 工作记忆里的一条。
     *
     * <p>{@code weight} 是<b>装进来时</b>的权重, 用于淘汰与排序; 它不是"她此刻觉得多重要" ——
     * 后者要等认知环节重算。把它做成可变字段会让工作记忆变成一个会自己变心的对象,
     * 而"这一轮她是怎么想的"就再也回放不出来了。
     */
    public record Entry(String id, EntryKind kind, String content, String sourceObjectId,
                        double weight, Instant at) {

        public Entry {
            Objects.requireNonNull(id, "工作记忆的条目必须有身份 —— 忘记一条要靠它");
            Objects.requireNonNull(kind, "条目必须说明它是什么");
            Objects.requireNonNull(content, "条目的内容不能为空");
            Objects.requireNonNull(at, "条目必须带时刻 —— 不许读系统时钟");
            sourceObjectId = sourceObjectId == null ? "" : sourceObjectId;
        }

        public boolean fromObject() {
            return !sourceObjectId.isEmpty();
        }

        public String describe() {
            return "[" + kind.label() + "] " + content
                    + (fromObject() ? " (来自 " + sourceObjectId + ")" : "");
        }

        @Override
        public String toString() {
            return describe();
        }
    }
}
