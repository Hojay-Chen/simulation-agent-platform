package com.luxera.companion.human.mind.memory;

import java.time.Instant;
import java.util.List;

/**
 * V2.2 §3.4.1 —— <b>情景记忆: "那时候发生了什么"</b>。
 *
 * <h2>它为什么存在</h2>
 * 因为"她是一个有过去的人"不是靠一条条事实堆出来的。回答"她今天怎么了"
 * 需要的是<b>一段时间里按顺序发生的若干件事</b>, 而不是"她喜欢咖啡"这种命题。
 * 这也是唯一一种能回答"她最近怎么样"的记忆。
 *
 * <h2>它的检索入口是时间, 不是关键词</h2>
 * {@link #between(Instant, Instant)} 是这个接口上最重要的方法。
 * 一个只有 {@code search(keyword)} 的情景记忆实现, 会在需要"她今天做了什么"时
 * 逼调用方去猜关键词 —— 而猜不中的后果是"她今天什么都没做"
 * 这个<b>看起来像事实的错误结论</b>。
 *
 * <h2>它不负责什么</h2>
 * <ul>
 *   <li><b>不负责遗忘</b>。什么时候该忘是 {@link MemoryStore} 的策略。
 *       在这里加一个 {@code decay()} 会让"她记不清了"变成一个可以随时被任一处代码
 *       触发的副作用, 而它应当是一个可以统一调参的<b>策略</b>。</li>
 *   <li><b>不是流水账</b>。记的是她<b>注意到的事</b> —— 而"注意到"由
 *       {@code AttentionService} 决定。所有刺激都记下来的话, 情景记忆会退化成
 *       一份与 {@code RealtimeEventQueue} 重复的日志, 而检索它等于重新跑一遍注意力。</li>
 *   <li><b>不存正文</b>。见 {@link MemoryRecord} 的禁令。</li>
 * </ul>
 *
 * <h2>它为什么不 extends {@link MemoryStore}</h2>
 * 因为它<b>不是</b> {@code MemoryStore} 的一个视图。把这五个接口做成
 * {@code MemoryStore} 的子接口看起来很整齐, 实际上会把"某一种记忆可以单独接一个
 * 实现"(比如将来的向量库只用于语义记忆)这条路堵死 —— 因为那时
 * {@code MemoryStore} 的其余四个方法必须一起实现。
 * 五个接口各自独立, 一个实现类可以同时实现五个 —— 这是组合, 不是继承。
 */
public interface EpisodicMemory {

    /** 记下她经历的一件事。 */
    void remember(MemoryRecord episode);

    /** 某段时间里她经历的事, 按发生时刻升序。 */
    List<MemoryRecord> between(Instant from, Instant to);

    /** 最近 {@code limit} 条, 最新的在前。 */
    List<MemoryRecord> recent(int limit);

    /** 情景记忆的条数 —— 诊断面板与"她最近很安静"这类判断用。 */
    int episodeCount();
}
