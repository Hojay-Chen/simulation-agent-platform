package com.luxera.companion.runtime.v11;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;

/**
 * V11 §25 —— <b>迁移纪律的开关</b>: Adapter → Shadow → Dual Run → Cutover → Cleanup。
 *
 * <p>与 {@code V10HotpathGateway} 的两个开关同构, 语义也刻意保持一致:
 *
 * <pre>
 *   enabled=false, shadow=false  完全走 V10 老链(V11 一行不参与)
 *   enabled=false, shadow=true   并跑 V11 判定, 只记录 diff, 不影响任何行为(默认)
 *   enabled=true,  shadow=*      V11 接管送达主链, 老链不再执行
 * </pre>
 *
 * <h2>为什么默认 shadow=true 而 enabled=false</h2>
 * 因为 V11 改的是 {@code AgentRuntime.onChatMessageDelivered} —— 本轮<b>唯一一处会让
 * 53 个 agent 行为真的发生变化</b>的地方。它不能靠"读代码觉得没问题"上线:
 * 新门的判定是"她注意到没有", 而这个判定与老链的判定在真实流量下差多少、
 * 差在哪, <b>只有并排跑过才知道</b>。默认 shadow 让这次改动在合并的第一天就
 * 开始积累证据, 而代价为零(判定是纯计算, 不写库、不发事件)。
 *
 * <h2>shadow 下的硬约束</h2>
 * shadow 模式<b>只允许计算, 不允许产生副作用</b> —— 不写阶梯事件、不发通知、不读正文。
 * 一旦 shadow 会写库, 它就不再是"观察", 而是一次没人知道的静默上线:
 * 数据库里出现阶梯记录, 信箱消费者开始处理它们, 于是 agent 的行为<b>真的</b>变了,
 * 而开关看起来还是关的。见 {@link V11DeliveryPath#assess} 与 {@link V11DeliveryPath#deliver}
 * 的分工 —— 前者无副作用, 后者才有。
 */
@Component
@Slf4j
public class V11RuntimeSwitch {

    @Value("${app.v11.runtime.enabled:false}")
    private boolean enabled;

    @Value("${app.v11.runtime.shadow:true}")
    private boolean shadow;

    public boolean isEnabled() {
        return enabled;
    }

    public boolean isShadow() {
        return shadow;
    }

    /** 要不要参与这件事。两个都关时连判定都不算。 */
    public boolean isActive() {
        return enabled || shadow;
    }

    @PostConstruct
    void announce() {
        if (enabled) {
            log.warn("[V11] 送达主链已由 V11 接管(enabled=true) —— 阶梯事件与设备读取生效中");
        } else if (shadow) {
            log.info("[V11] shadow 模式: 并跑 V11 判定并记录 diff, 不影响任何行为");
        } else {
            log.info("[V11] 关闭: 完全走既有链路");
        }
    }
}
