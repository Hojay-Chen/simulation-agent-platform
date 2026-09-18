package com.luxera.companion.mind;

import java.time.LocalDateTime;

/**
 * V11 §8.3 —— <b>一个回合什么时候算说完</b>。
 *
 * <h2>它不是 debounce, 但它今天只能做到 debounce</h2>
 * 设计文档 §8.3 列了六个判据: 时间间隔、消息条数、对方是否正在输入、语义是否完整、
 * 她自己的状态、她是不是正在做别的事。本类只实现了前两个, 另外四个今天<b>拿不到</b>:
 * <ul>
 *   <li>"对方正在输入" —— 契约里没有这个信号({@code ChatWorldPort} 只有消息与状态)。</li>
 *   <li>"语义是否完整" —— 要看懂才判断得了, 而看懂需要一个 LLM 回合; 用它来<b>决定</b>
 *       要不要开一个 LLM 回合是循环论证。正确的位置是 Phase 4 的认知决策
 *       ({@code REPLY} 之外还要有 {@code WAIT}), 那时它才有一个不循环的形态。</li>
 *   <li>"她的状态 / 是否正在做别的事" —— {@code agent_mind_states} 从今天起记了
 *       (她手上挂着什么), 但让窗口去读它需要先有 Phase 4 的决策输入。</li>
 * </ul>
 * 所以本类刻意只做"静默窗口 + 硬上限"这两条, 并把另外四条记在这里 ——
 * 一个假装自己会判断语义的窗口, 比一个诚实地只看时间的窗口更难发现问题。
 *
 * <h2>两个窗口, 回答两个不同的问题</h2>
 * <pre>
 *   quietWindow  她说完一句话之后, 等多久确认"说完了"     —— 太短则三句话变三个回合
 *   maxWindow    一条线最多能拖多久不给回音               —— 太长则对方觉得她死了
 * </pre>
 * 没有 maxWindow 的话, 一个持续输入十分钟的对方会让她的回合永远封不了口 ——
 * 而"她一直没回"正是这套设计要修的问题, 不能反过来由它制造出来。
 *
 * @param quietWindowMs 静默窗口
 * @param maxWindowMs   从回合开始算的硬上限
 * @param maxMessages   攒到这么多条就不再等(注意是"不再等", 不是"丢掉多的")
 */
public record TurnWindow(int quietWindowMs, int maxWindowMs, int maxMessages) {

    /** 出厂值: 4 秒静默、45 秒硬上限、最多攒 8 条。 */
    public static final int DEFAULT_QUIET_WINDOW_MS = 4_000;
    public static final int DEFAULT_MAX_WINDOW_MS = 45_000;
    public static final int DEFAULT_MAX_MESSAGES = 8;

    public TurnWindow {
        // 校验放在构造器里: 配错了要在<b>启动时</b>炸, 而不是在某个深夜表现为
        // "她突然每句话都回一次"(窗口 0)或者"她再也不回话"(窗口无限大)
        if (quietWindowMs < 1) {
            throw new IllegalArgumentException("quietWindowMs 必须 > 0, 收到 " + quietWindowMs);
        }
        if (maxWindowMs < quietWindowMs) {
            throw new IllegalArgumentException(
                    "maxWindowMs(" + maxWindowMs + ") 不能小于 quietWindowMs(" + quietWindowMs + ") —— 那样静默窗口永远轮不到生效");
        }
        if (maxMessages < 1) {
            throw new IllegalArgumentException("maxMessages 必须 >= 1, 收到 " + maxMessages);
        }
    }

    public static TurnWindow defaults() {
        return new TurnWindow(DEFAULT_QUIET_WINDOW_MS, DEFAULT_MAX_WINDOW_MS, DEFAULT_MAX_MESSAGES);
    }

    /** 静默到这一刻就该封口了。 */
    public LocalDateTime quietSealAt(LocalDateTime lastMessageAt) {
        return lastMessageAt == null ? null : lastMessageAt.plusNanos(quietWindowMs * 1_000_000L);
    }

    /** 无论安静与否, 到这一刻必须封口。 */
    public LocalDateTime hardSealAt(LocalDateTime openedAt) {
        return openedAt == null ? null : openedAt.plusNanos(maxWindowMs * 1_000_000L);
    }

    /** 攒够了, 不必再等。 */
    public boolean full(int turnSize) {
        return turnSize >= maxMessages;
    }
}
