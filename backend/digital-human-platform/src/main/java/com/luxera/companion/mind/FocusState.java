package com.luxera.companion.mind;

import java.time.LocalDateTime;

/**
 * V11 §9.2 / §23.4 —— <b>她此刻把注意力放在哪儿</b>。
 *
 * <p>与 {@link WorkingThread} 的分工是一条硬边界:
 * <ul>
 *   <li>{@code WorkingThread} 是"账" —— 有哪几条线挂着、各欠多少条没看。它严格按事实记,
 *       不需要读懂任何东西。</li>
 *   <li>{@code FocusState} 是"<b>唯一一处</b>她真正在想的事", 所以它只能由<b>读完之后</b>的
 *       认知写入。它的值来自 {@code PerceptionEngine} 抽出来的话题。</li>
 * </ul>
 * 两者合成起来才回答得了"她现在什么状态": 注意力在哪儿 + 手上还欠着谁。
 *
 * @param what      她正关注的事(认知后的结论, 不是猜的)
 * @param source    这件事是从哪次会话来的 —— 没有它, 两条线打架时无法判断
 * @param intensity 她抓住这件事的力度
 * @param since     从什么时候起
 */
public record FocusState(String what, String source, double intensity, LocalDateTime since) {

    public FocusState {
        intensity = clamp01(intensity);
    }

    /**
     * 她还没想过任何事时的样子 —— 一个"没有", 而不是一个假的默认话题。
     *
     * <p>刻意<b>不</b>造一个像"日常闲聊"这样的占位符: 那种值会一路流进上下文,
     * 让"她还什么都没想"变成一句看起来像事实的话。
     */
    public static FocusState none() {
        return new FocusState(null, null, 0, null);
    }

    public boolean isPresent() {
        return what != null && !what.isBlank();
    }

    /** 同一处关注, 只是时间过去了(注意力会淡)。 */
    public FocusState decayedTo(double newIntensity) {
        return new FocusState(what, source, newIntensity, since);
    }

    private static double clamp01(double v) {
        if (Double.isNaN(v)) return 0;
        return Math.max(0, Math.min(1, v));
    }
}
