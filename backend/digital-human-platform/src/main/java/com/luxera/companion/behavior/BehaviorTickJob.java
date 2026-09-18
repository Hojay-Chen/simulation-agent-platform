package com.luxera.companion.behavior;

import com.luxera.companion.runtime.v11.ProactiveActionRecorder;
import com.luxera.companion.runtime.v11.V11ProactiveSwitch;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * §四十一 行为 Tick: 数字人的世界每 5 分钟推进一次。
 *
 * <p>取代 的"15 分钟主动检查" —— 不再是"要不要发主动消息"的定时器,
 * 而是"她此刻最可能做什么"的中央行为选择(睡觉/看手机/联系用户/联系朋友/继续生活/发呆)。
 *
 * <h2>V11 Phase 5 之后它是什么</h2>
 * 它变成了<b>双跑期的老链那一半</b>, 而且只在没切流时跑:
 * <pre>
 *   enabled=false (shadow)  老链照跑, 产出记进对照账本; 新链同时投信, 消费者只记账
 *   enabled=true            老链停摆(她的事改由 agent_schedule 驱动), 这个 tick 只剩空壳
 * </pre>
 * <b>shadow 期老链必须继续跑</b>, 否则"对照"这个词就没有第二边了 —— 那会是一次
 * 开关看起来还关着的静默切流, 也正是 {@code ProactiveActionRecorder} 存在的理由。
 *
 * <p>反过来, {@code enabled=true} 时如果这里还在跑, 她的主动行为会<b>发生两次</b>:
 * 老链 tick 选一次并执行, 新链拆信又选一次并执行。那不是"更主动", 那是重复发言 ——
 * 而重复发言在外部看起来与"她心情很好"一模一样, 很难被当成故障报上来。所以这个
 * 分叉不是效率优化, 是切流的正确性条件。
 *
 * <p>Phase 6 会连同老链一起删掉本类。在那之前它必须完整地活着 —— 它今天仍然是
 * 生产上唯一真的会让她开口的东西。
 */
@Slf4j
@Component
public class BehaviorTickJob {

    private final BehaviorEngine behaviorEngine;
    private final V11ProactiveSwitch v11;
    private final ProactiveActionRecorder recorder;

    public BehaviorTickJob(BehaviorEngine behaviorEngine, V11ProactiveSwitch v11,
                           ProactiveActionRecorder recorder) {
        this.behaviorEngine = behaviorEngine;
        this.v11 = v11;
        this.recorder = recorder;
    }

    @Scheduled(cron = "${app.scheduler.behavior-tick-cron}")
    public void run() {
        if (v11.isEffective()) {
            // 已切流: 她的节拍由 WakeupRearmJob + agent_schedule 驱动。见类注释最后一段。
            return;
        }
        try {
            for (BehaviorOutcome outcome : behaviorEngine.evaluateAll(LocalDateTime.now())) {
                recorder.recordOld(outcome.action());
            }
        } catch (Exception e) {
            log.warn("[BehaviorTick] 失败: {}", e.getMessage());
        }
    }
}
