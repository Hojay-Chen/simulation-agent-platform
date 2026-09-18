package com.luxera.companion.runtime.v11;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;

/**
 * V11 §8 / §25.1 —— <b>回合合并的开关</b>。
 *
 * <pre>
 *   enabled=false, shadow=false  连聚合都不跑(零成本)
 *   enabled=false, shadow=true   跑聚合、记数字, 但认知仍然是"一次送达一次"(默认)
 *   enabled=true,  shadow=*      认知改由回合封口驱动 —— 三句话一个回合
 * </pre>
 *
 * <h2>为什么不复用 {@link V11RuntimeSwitch}</h2>
 * 因为它们是<b>两件独立的行为变化</b>, 而一次只该翻一个开关:
 * <ul>
 *   <li>{@code app.v11.runtime.*} 决定"<b>她会不会注意到</b>"(Phase 2)</li>
 *   <li>{@code app.v11.turns.*} 决定"<b>连着来的几句话算几次认知</b>"(Phase 3)</li>
 * </ul>
 * 合成一个开关的后果不是省事, 而是切流那天<b>无法归因</b>: 行为变了, 而你不知道
 * 是"注意门的差异"造成的还是"回合合并"造成的, 于是两个都要回滚。
 *
 * <h2>与 runtime 开关的先后关系(必须知道的一件事)</h2>
 * 回合合并<b>长在 V11 送达主链上</b>: 聚合的是"她注意到了的那几条消息", 而
 * "她注意到没有"是 {@code app.v11.runtime} 的判定。所以在
 * {@code app.v11.runtime.enabled=false} 时打开本开关<b>不会有任何效果</b> ——
 * 老链没有"注意到了但还没读"这个状态, 也就没有可聚合的东西。
 * 这一点不靠文档记住: 启动时会打一条 WARN, 诊断端点也会直说。
 */
@Component
@Slf4j
public class V11TurnsSwitch {

    @Value("${app.v11.turns.enabled:false}")
    private boolean enabled;

    @Value("${app.v11.turns.shadow:true}")
    private boolean shadow;

    private final V11RuntimeSwitch runtimeSwitch;

    public V11TurnsSwitch(V11RuntimeSwitch runtimeSwitch) {
        this.runtimeSwitch = runtimeSwitch;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public boolean isShadow() {
        return shadow;
    }

    /** 要不要跑聚合这件事(两种模式都跑, 只是结果的去处不同)。 */
    public boolean isActive() {
        return enabled || shadow;
    }

    /**
     * 开关真的生效了吗 —— {@code enabled} 为真但送达主链没接管时, 它只是一行配置。
     * 诊断端点与启动日志都用这个判断, 而不是各自去拼两个开关。
     */
    public boolean isEffective() {
        return enabled && runtimeSwitch.isEnabled();
    }

    @PostConstruct
    void announce() {
        if (enabled && !runtimeSwitch.isEnabled()) {
            log.warn("[V11] turns.enabled=true 但 runtime.enabled=false —— 回合合并长在 V11 送达主链上, "
                    + "今天不会有任何效果(老链没有'注意到了但还没读'这个状态)。先开 app.v11.runtime.enabled。");
        } else if (enabled) {
            log.warn("[V11] 回合合并已接管: 连着来的几句话会并成一个认知回合");
        } else if (shadow) {
            log.info("[V11] turns shadow: 跑聚合状态机并记录合并率, 认知仍然一次送达一次");
        } else {
            log.info("[V11] turns 关闭: 不跑聚合");
        }
    }
}
