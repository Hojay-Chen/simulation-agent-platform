package com.luxera.companion.behavior;

import com.luxera.companion.runtime.v11.ProactiveActionRecorder;
import com.luxera.companion.runtime.v11.V11ProactiveSwitch;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * V11 Phase 5 —— <b>双跑期的老链那一半</b>。
 *
 * <p>这个类在 Phase 6 会被删掉, 但在那之前它有一个必须钉死的性质:
 * <b>切流之后它必须停</b>。不停的话, 老链 tick 选一次并执行, 新链拆信又选一次并执行 ——
 * 她的主动行为发生两次。而"她一次说了两条"在外观上与"她心情很好"没有区别,
 * 不会被当成故障报上来, 只会被当成她话多。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class BehaviorTickJobTest {

    @Mock private BehaviorEngine behaviorEngine;
    @Mock private V11ProactiveSwitch v11;
    @Mock private ProactiveActionRecorder recorder;

    private BehaviorTickJob job;

    @BeforeEach
    void setUp() {
        job = new BehaviorTickJob(behaviorEngine, v11, recorder);
    }

    private BehaviorOutcome outcome(BehaviorAction action) {
        return new BehaviorOutcome(action, "想问问", 0.5, "老链", LocalDateTime.now());
    }

    @Nested
    @DisplayName("还没切流: 老链继续跑, 并且记账")
    class Shadow {

        @BeforeEach
        void oldChainOnly() {
            when(v11.isEffective()).thenReturn(false);
        }

        @Test
        void theOldChainStillDecidesAndActs() {
            // shadow 期老链必须继续跑 —— 否则"对照"就没有第二边了, 那会是一次
            // 开关看起来还关着的静默切流
            when(behaviorEngine.evaluateAll(any(LocalDateTime.class)))
                    .thenReturn(List.of(outcome(BehaviorAction.SEND_PROACTIVE_MESSAGE)));

            job.run();

            verify(behaviorEngine).evaluateAll(any(LocalDateTime.class));
            verify(recorder).recordOld(BehaviorAction.SEND_PROACTIVE_MESSAGE);
        }

        @Test
        void everyOutcomeIsHandedToTheLedgerInOrder() {
            when(behaviorEngine.evaluateAll(any(LocalDateTime.class))).thenReturn(List.of(
                    outcome(BehaviorAction.SEND_PROACTIVE_MESSAGE),
                    outcome(BehaviorAction.DO_NOTHING),
                    outcome(BehaviorAction.CHECK_PHONE)));

            job.run();

            var inOrder = inOrder(recorder);
            inOrder.verify(recorder).recordOld(BehaviorAction.SEND_PROACTIVE_MESSAGE);
            inOrder.verify(recorder).recordOld(BehaviorAction.DO_NOTHING);
            inOrder.verify(recorder).recordOld(BehaviorAction.CHECK_PHONE);
        }

        @Test
        void anEmptyTickIsNotAFailure() {
            when(behaviorEngine.evaluateAll(any(LocalDateTime.class))).thenReturn(List.of());

            job.run();

            verify(recorder, never()).recordOld(any());
        }
    }

    @Nested
    @DisplayName("已切流: 老链必须闭嘴")
    class AfterCutover {

        @BeforeEach
        void cutover() {
            when(v11.isEffective()).thenReturn(true);
        }

        @Test
        void theOldChainDoesNotRunAtAll() {
            job.run();

            // 这一条是切流的正确性条件, 不是效率优化。老链还在跑的话, 她的一次主动行为
            // 会同时由两条链各执行一次 —— 重复发言。
            verify(behaviorEngine, never()).evaluateAll(any(LocalDateTime.class));
            verifyNoInteractions(recorder);
        }

        @Test
        void aFailureInTheEngineNeverEscapesOntoTheSchedulerThread() {
            // 共用调度线程上一旦抛出去, 后面所有 @Scheduled 任务都会跟着停 —— 包括
            // 那个 5 秒一次的 outbox-relay。所以这里连"已切流"那条分支也不许抛。
            when(v11.isEffective()).thenReturn(false);
            when(behaviorEngine.evaluateAll(any(LocalDateTime.class)))
                    .thenThrow(new RuntimeException("库挂了"));

            assertDoesNotThrow(job::run);
        }
    }
}
