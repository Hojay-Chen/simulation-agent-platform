package com.luxera.companion.runtime.v11;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;

/**
 * V11 §17.2 —— <b>主动行为的开关</b>(Phase 5)。
 *
 * <pre>
 *   enabled=false, shadow=false  触发器不发事件, 主动行为与今天一字不差
 *   enabled=false, shadow=true   触发器发事件; 消费者拆信并算出"她会做什么", 但不动手(默认)
 *   enabled=true,  shadow=*      消费者拆信并让它发生
 * </pre>
 *
 * <h2>为什么这是第四个开关</h2>
 * 前三个改的都是"她怎么回应", 这一个改的是<b>谁先开口</b>:
 * <pre>
 *   app.v11.runtime.*    她会不会注意到        (Phase 2, 改送达)
 *   app.v11.turns.*      连着来的话算几次认知   (Phase 3, 改聚合)
 *   app.v11.cognition.*  她决定做不做什么      (Phase 4, 改回复)
 *   app.v11.proactive.*  她会不会先开口        (Phase 5, 改发起)
 * </pre>
 * 第四条与前三条<b>正交</b>: 一个完全不主动的数字人可以有完美的注意与决策;
 * 一个整天主动找人说话的数字人可以什么消息都不回。合成一个开关的话,
 * "她最近怎么老是自己找我说话"和"我发消息她怎么不回"就无法分开回滚。
 *
 * <h2>它有两侧, 而两侧必须用同一个判断</h2>
 * 发事件的那一侧(触发器)与收事件的那一侧({@code ProactiveActionConsumer})都要问本类。
 * 两侧不一致会产生一个安静的故障: 发了而没人收, 信就留在信箱里
 * ({@code AgentMailbox} 对"没有消费者"的处理是<b>留在 PENDING</b>), 于是切流那天
 * 所有积压的事件会一起被拆开 —— 表现为她一口气发了几十条主动消息。
 * 所以消费者在开关关掉时是<b>拆掉信、记一笔、什么都不做</b>(信变成 CONSUMED),
 * 而不是"不看"。宁可丢掉一封已经决定不处理的信, 也不能攒着。
 *
 * <h2>shadow 的纪律: 不许写世界, 但允许写她自己的时钟</h2>
 * shadow 只允许算。第三档的"算"在这里要特别小心: 主动行为的决策引擎
 * ({@code BehaviorEngine.evaluate}) <b>边选边做</b>, 所以 shadow 走的是
 * {@code select} 而不是 {@code evaluate} —— 见 {@code ProactiveActionConsumer}。
 * 如果 shadow 调的是那个真的会发消息的方法, 它就不是 shadow, 是一次没人知道的上线。
 *
 * <p>Phase 5 把这条纪律的边界说细一点, 因为它的字面表述("一字不改")在这里会误导:
 * shadow 期确实会写两个东西 —— <b>账本</b>({@code ProactiveActionRecorder})与
 * <b>她自己的时钟</b>({@code agent_schedule})。理由是这两样都不可被外部观测到:
 * 一行闹钟只会产出一封由 shadow 消费者拆开、只记一笔的信, 她不会因此说出一句话。
 *
 * <p>不可以写的是<b>世界</b>: 消息、关系、活动、通知、状态事件。判据很干脆 ——
 * 如果对方(或任何一个读聊天平台的人)能看出差别, 那就越界了。Phase 1~3 的 shadow
 * 也是这条边界(它们写 {@code agent_mind_states} 与账本, 同样看不见)。
 */
@Component
@Slf4j
public class V11ProactiveSwitch {

    @Value("${app.v11.proactive.enabled:false}")
    private boolean enabled;

    @Value("${app.v11.proactive.shadow:true}")
    private boolean shadow;

    public boolean isEnabled() {
        return enabled;
    }

    public boolean isShadow() {
        return shadow;
    }

    /** 算不算/shadow 不 shadow 都算"要用" —— 两个都关时触发器一个字都不发。 */
    public boolean isActive() {
        return enabled || shadow;
    }

    /** 拆开信之后要不要真的动手。只有这一档会改变世界上发生的事。 */
    public boolean isEffective() {
        return enabled;
    }

    @PostConstruct
    void announce() {
        if (enabled) {
            log.warn("[V11] 主动行为已接管: 触发器只发事件, 由消费者拆信并行动");
        } else if (shadow) {
            log.info("[V11] proactive shadow: 发事件、算她会做什么并记录, 不动手");
        } else {
            log.info("[V11] proactive 关闭: 既不排闹钟也不发事件");
        }
    }
}
