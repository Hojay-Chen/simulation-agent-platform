package com.luxera.companion.wakeup;

import com.luxera.companion.behavior.BehaviorEngine;
import com.luxera.companion.persona.Companion;
import com.luxera.companion.persona.CompanionRepository;
import com.luxera.companion.runtime.v11.V11ProactiveSwitch;
import com.luxera.companion.world.AgentEventType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * V11 §17.2 —— <b>她永远有一个闹钟</b>。
 *
 * <p>这个类是 {@code BehaviorTickJob} 在 V11 里的替身, 所以它有一个比"能不能排上"更重要的
 * 断言: {@link Handover#relationshipPressureStillAdvancesAfterTheOldChainStops}。
 * §三十九 的关系维护压力原来寄生在老链的 tick 里 —— 切流之后那一步必须有人接住,
 * 而漏掉它的症状不是报错, 是"她再也不主动找人"。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class WakeupRearmJobTest {

    private static final String AGENT = "agent-1";

    @Mock private CompanionRepository companions;
    @Mock private AgentWakeupService wakeups;
    @Mock private BehaviorEngine behaviorEngine;
    @Mock private V11ProactiveSwitch v11;

    private WakeupRearmJob job;

    @BeforeEach
    void setUp() {
        job = new WakeupRearmJob(companions, wakeups, behaviorEngine, v11);
        ReflectionTestUtils.setField(job, "rearmMinutes", 30);
        ReflectionTestUtils.setField(job, "jitter", 0.25);
        when(v11.isActive()).thenReturn(true);
        when(companions.findRunnable()).thenReturn(List.of(companion(AGENT)));
        when(wakeups.nextWakeupOf(anyString())).thenReturn(null);
        when(wakeups.schedule(anyString(), any(), any(), anyString(), anyString())).thenReturn(true);
    }

    private Companion companion(String id) {
        Companion c = new Companion();
        c.setId(id);
        return c;
    }

    private LocalDateTime scheduledAt() {
        ArgumentCaptor<LocalDateTime> at = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(wakeups).schedule(eq(AGENT), at.capture(), any(), anyString(), anyString());
        return at.getValue();
    }

    // ─────────────────────────── 补位 ───────────────────────────

    @Nested
    @DisplayName("她手上必须一直有一个时刻")
    class KeepOneAlarm {

        @Test
        void anAgentWithoutAnAlarmGetsOne() {
            job.rearm();

            ArgumentCaptor<String> key = ArgumentCaptor.forClass(String.class);
            verify(wakeups).schedule(eq(AGENT), any(LocalDateTime.class),
                    eq(AgentEventType.SCHEDULED_WAKEUP), key.capture(), anyString());
            assertEquals(AgentWakeupService.SRC_LIFE, key.getValue(),
                    "生命节律那一类, 不是某个具体决策的复查");
        }

        @Test
        void anAgentThatAlreadyHasOneIsLeftAlone() {
            when(wakeups.nextWakeupOf(AGENT)).thenReturn(LocalDateTime.now().plusMinutes(3));

            job.rearm();

            // 无条件重排会把她自己的时间表碾平: 每 5 分钟被推后一次, 于是它永远不会到点
            verify(wakeups, never()).schedule(anyString(), any(), any(), anyString(), anyString());
        }

        @Test
        void theNextMomentIsWithinTheJitteredBand() {
            ReflectionTestUtils.setField(job, "jitter", 0.0);   // 钉死抖动才好断言区间

            job.rearm();

            long minutes = Duration.between(LocalDateTime.now(), scheduledAt()).toMinutes();
            assertTrue(minutes >= 29 && minutes <= 30, "基准 30 分钟, 却排到了 " + minutes + " 分钟");
        }

        @Test
        void theJitterSpreadsTheWakeUpsOut() {
            // 53 个 agent 在同一秒醒来会共用一根调度线程与一个 LLM 出口 —— 抖动不是装饰
            when(companions.findRunnable()).thenReturn(
                    List.of(companion("a"), companion("b"), companion("c"), companion("d")));
            ArgumentCaptor<LocalDateTime> at = ArgumentCaptor.forClass(LocalDateTime.class);

            job.rearm();

            verify(wakeups, times(4)).schedule(anyString(), at.capture(), any(), anyString(), anyString());
            long distinct = at.getAllValues().stream().distinct().count();
            assertTrue(distinct > 1, "四个 agent 排到了同一个时刻: 抖动没生效");
        }

        @Test
        void nothingHappensWhileTheSwitchIsOff() {
            when(v11.isActive()).thenReturn(false);

            job.rearm();

            verifyNoInteractions(wakeups);
            verifyNoInteractions(behaviorEngine);
        }
    }

    // ─────────────────────────── 交接 ───────────────────────────

    @Nested
    @DisplayName("切流之后, 老链那一步必须有人接住")
    class Handover {

        @Test
        void relationshipPressureStillAdvancesAfterTheOldChainStops() {
            // decayConnectionPressure 原来寄生在 BehaviorEngine.evaluateAll 里。切流之后
            // evaluateAll 不再跑 —— 如果这里也不调, "沉默越久越想联系"就静默失效了:
            // 不报错, 只表现为她再也不主动找人, 而读代码很难发现少了什么。
            job.rearm();

            verify(behaviorEngine).prepare(eq(AGENT), any(LocalDateTime.class));
        }

        @Test
        void oneBrokenAgentDoesNotStopTheOthers() {
            when(companions.findRunnable())
                    .thenReturn(List.of(companion("a"), companion("b")));
            doThrow(new RuntimeException("库抖了一下")).when(behaviorEngine)
                    .prepare(eq("a"), any(LocalDateTime.class));

            assertDoesNotThrow(job::rearm);

            verify(wakeups).schedule(eq("b"), any(), any(), anyString(), anyString());
        }

        @Test
        void aPausedAgentNeverGetsAnAlarm() {
            // 排除 paused 不是本类自己做的, 而是因为它用 findRunnable() —— 这条用例
            // 钉住的是"它没有绕开那个查询自己 findAll"。给一个被暂停的 agent 上发条
            // 会让她的闹钟在暂停期间一封封响过去, 攒出一叠她根本处理不了的信。
            when(companions.findRunnable()).thenReturn(List.of());

            job.rearm();

            verify(wakeups, never()).schedule(anyString(), any(), any(), anyString(), anyString());
        }
    }
}
