package com.luxera.companion.human.mind.memory;

import java.time.Instant;
import java.util.List;
import java.util.Set;

/**
 * V2.2 §3.4.1 —— <b>长期记忆的端口</b>。五种记忆的汇合处, 也是持久化唯一该发生的地方。
 *
 * <h2>为什么它是一个"端口"而不是一个实现</h2>
 * 因为这个设计里<b>只有这一件事</b>可以被换成外部技术: 存储。其余的一切
 * (感知怎么解释、注意力怎么打折、决定怎么做)都是本设计的规则, 换成别的技术
 * 就不再是这个设计。而记忆不一样 —— 今天可以是内存, 明天可以是 Postgres,
 * 后天可以是向量库, 而<b>"她记得什么"不因为换了库而改变</b>。
 *
 * <p>所以在 V2.2 的这一轮里, 本接口只提供:
 * <ul>
 *   <li>五种检索入口(继承自五个接口);</li>
 *   <li>一个统一的写入点 {@link #store};</li>
 *   <li>一个跨种类的线索检索 {@link #recall}。</li>
 * </ul>
 * 持久化的实现留给别人 —— 本轮提供一个可用的内存实现
 * ({@link InMemoryMemoryStore}), 它的正确性由测试保证, 它的性能<b>不作为承诺</b>。
 *
 * <h2>它不负责什么</h2>
 * <ul>
 *   <li><b>不做事件溯源</b>。它不订阅 {@code EventFabric} —— 记忆记的是"她注意到的",
 *       而"注意到"由注意力决定。一个直接订阅全部事件的记忆实现会绕过注意力,
 *       于是"她没看见"与"她不记得"变成同一件事, 而它们必须能分开。</li>
 *   <li><b>不做检索排序的最终裁决</b>。{@link #recall} 给出的是<b>按重要度</b>的排序;
 *       要按当下处境相关度排序, 那是认知环节的事(它知道她此刻在做什么)。</li>
 * </ul>
 *
 * <h2>实现者必须遵守的两条</h2>
 * <ol>
 *   <li><b>时刻全部来自参数</b>。实现里不许出现 {@code Instant.now()} ——
 *       一个自己读时钟的记忆库会让回放出来的"她记得什么"随运行时刻漂移;</li>
 *   <li><b>{@code MemoryRecord} 是不可变的</b>。写入时若实现要加字段(比如向量),
 *       要新建对象或存在自己的旁表里, 而不是改那个 record。</li>
 * </ol>
 */
public interface MemoryStore extends EpisodicMemory, SemanticMemory, SharedMemory,
        RelationshipMemory, SelfMemory {

    /**
     * 写入一条记忆 —— <b>统一的入口</b>。
     *
     * <p>它会按 {@link MemoryRecord#kind()} 分流到对应的那一种,
     * 并调用 {@link MemoryRecord#requireSubject()} 做一次形状检查。
     * 五个接口各自也有自己的写入方法(它们更清楚语义), 但任何一条落到存储里的记录
     * 都应当能在 {@link #all()} 里被看到 —— 否则"她一共记得多少事"这个问题
     * 会在几种记忆之间各答一半。
     */
    void store(MemoryRecord record);

    /**
     * 跨种类的线索检索 —— "她关于'考试'都记得什么"。
     *
     * <p>排序规则是<b>先按线索命中数、再按重要度、最后按发生时刻</b>。
     * 这个次序是刻意的: 命中数代表"与这次检索有多贴题",
     * 而重要度只在同样贴题的条目之间起作用 —— 反过来(先按重要度)会让
     * "她最在意的那件事"永远占满每一次检索的前几名。
     */
    List<MemoryRecord> recall(Set<String> cues, int limit);

    /** 某段时间、某一种记忆里的全部条目。 */
    List<MemoryRecord> slice(MemoryKind kind, Instant from, Instant to);

    /** 存储里的全部记录 —— 诊断、导出与测试用。 */
    List<MemoryRecord> all();

    /** 总条数。 */
    int size();

    /** 清空 —— 用于回放与测试。 */
    void clear();
}
