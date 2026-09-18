package com.luxera.companion.runtime.v11;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;

/**
 * V11 §25.1 —— <b>认知决策的开关</b>(Phase 4)。
 *
 * <pre>
 *   enabled=false, shadow=false  不产生决策对象, 回复路径与今天一字不差
 *   enabled=false, shadow=true   算出决策并记录它, 但回复与否仍由老链决定(默认)
 *   enabled=true,  shadow=*      回复与否由决策决定 —— 老链的七八个 return 退居为"执行细节"
 * </pre>
 *
 * <h2>为什么这是第三个开关, 而不是把前两个复用一下</h2>
 * 因为 V11 到此为止改了三件<b>互不相同</b>的事, 而切流那天必须能一件一件归因:
 * <pre>
 *   app.v11.runtime.*    她会不会注意到        (Phase 2, 改的是送达)
 *   app.v11.turns.*      连着来的话算几次认知   (Phase 3, 改的是聚合)
 *   app.v11.cognition.*  她决定做不做什么      (Phase 4, 改的是回复)
 * </pre>
 * 合成一个开关的代价不是省事, 而是回滚时<b>必须全部回滚</b> —— 你想撤掉"不回复"这条新行为,
 * 结果把回合合并也一起撤了, 于是合并率的证据链断掉, 得从头再跑一遍。
 *
 * <h2>它与前两个开关的依赖关系(和 turns 不同, 这次是真的没有依赖)</h2>
 * {@code turns} 长在 {@code runtime} 的主链上, 所以 runtime 没接管时 turns 是一行死配置。
 * 认知决策<b>不是</b>这样: 它挂在回复路径上, 而回复路径无论哪条主链在跑都存在 ——
 * 所以 {@link #isEffective()} 只问 {@code enabled} 自己。
 * 反过来, {@code runtime.enabled} 开着而 {@code cognition.enabled} 关着时,
 * 行为 === "老链的回复判断 + 新的注意门 + 回合合并"。这三种组合都是合法的中间态。
 *
 * <h2>shadow 的纪律</h2>
 * shadow <b>只允许算, 不允许改</b>: 决策对象算出来之后只进记录器, 不参与任何 {@code if}。
 * 一旦 shadow 能拦下一次回复, 它就不再是"观察", 而是一次没人知道的上线:
 * 开关看起来是关的, 而她已经不回消息了。见 {@link com.luxera.companion.cognition.CognitiveDecision}
 * 的"有损单向适配器"说明 —— 老链的走向可以被读成新词表, 但新词表不会被塞回老链。
 */
@Component
@Slf4j
public class V11CognitionSwitch {

    @Value("${app.v11.cognition.enabled:false}")
    private boolean enabled;

    @Value("${app.v11.cognition.shadow:true}")
    private boolean shadow;

    public boolean isEnabled() {
        return enabled;
    }

    public boolean isShadow() {
        return shadow;
    }

    /** 要不要算这个决策。两个都关时连算都不算 —— 决策本身也要走一遍感知输入。 */
    public boolean isActive() {
        return enabled || shadow;
    }

    /**
     * 开关真的在改变行为了吗。
     *
     * <p>刻意只看 {@code enabled}: 认知决策挂在回复路径上, 与送达主链无关
     * (对比 {@link V11TurnsSwitch#isEffective()} —— 那个必须同时看 runtime)。
     * 两处判断不一样是<b>对的</b>, 因为它们描述的是两件事的生效条件。
     */
    public boolean isEffective() {
        return enabled;
    }

    @PostConstruct
    void announce() {
        if (enabled) {
            log.warn("[V11] 认知决策已接管: 回复与否将由 CognitiveDecision 决定(不回复成为一个显式决策)");
        } else if (shadow) {
            log.info("[V11] cognition shadow: 算出决策并记录与老链的差异, 回复行为不变");
        } else {
            log.info("[V11] cognition 关闭: 不产生决策对象");
        }
    }
}
