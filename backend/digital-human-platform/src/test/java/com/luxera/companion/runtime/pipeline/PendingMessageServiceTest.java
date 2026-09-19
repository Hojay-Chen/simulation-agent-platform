package com.luxera.companion.runtime.pipeline;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link PendingMessageService} —— <b>"延后"这件事到底改了什么</b>。
 *
 * <h2>为什么这几条值得单独钉住</h2>
 *
 * 这里测的不是"能不能存一条待复查消息"(那件事一直是对的), 而是<b>推迟一次复查之后,
 * 那一行下一分钟还会不会到期</b>。这个区别看起来很小, 却是两份实现的分水岭:
 * 曾经的复查 Job 把"延后 30 分钟"写进了<b>另一张表</b>, 而 {@code next_review_at}
 * 一个字都没动。于是同一批消息每分钟被重新捞起来一次, 永远不结束 —— 一个月里
 * 攒下 12 万行失败记录, 而本表的 {@code review_count} 始终是 0(没有任何路径去加它)。
 *
 * <p>所以下面每一条断言都落在<b>这一行自己的列</b>上: 时刻、计数、状态。
 * 一张只会被读、不会被推进的表, 与一个永远不会到期的一次性闹钟是同一种东西。
 *
 * <h2>{@code postpone} 与 {@code deferReview} 的差别就是 {@code reviewCount}</h2>
 *
 * 这一条是刻意的, 而且它有代价 —— 一个"睡着了"就推后、不计数的 agent 可以无限期地
 * 把一条消息推下去。测试把两种行为都钉住, 是为了让那个代价看得见:
 * 睡着不吃预算(她还没想过这条), 忙/被打断吃预算(她想过并决定晚点回)。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PendingMessageServiceTest {

    private static final String PENDING_ID = "pm-1";
    private static final String MESSAGE_ID = "msg-1";

    @Mock
    private PendingMessageStateRepository repo;

    @InjectMocks
    private PendingMessageService service;

    private PendingMessageState pendingRow(int reviewCount) {
        PendingMessageState p = new PendingMessageState();
        p.setId(PENDING_ID);
        p.setMessageId(MESSAGE_ID);
        p.setCompanionId("c-1");
        p.setConversationId("conv-1");
        p.setUserId("u-1");
        p.setSenderText("在吗");
        p.setReviewCount(reviewCount);
        p.setNextReviewAt(LocalDateTime.now().minusMinutes(5));
        return p;
    }

    @Nested
    @DisplayName("deferReview —— 一次真正的复查")
    class DeferReview {

        @Test
        @DisplayName("推后时刻 + 计数 +1, 行仍在队列里")
        void pushesTimeAndCounts() {
            PendingMessageState row = pendingRow(0);
            when(repo.findById(PENDING_ID)).thenReturn(Optional.of(row));
            LocalDateTime at = LocalDateTime.now().plusMinutes(30);

            boolean kept = service.deferReview(PENDING_ID, at, "忙", null);

            assertTrue(kept, "第一次复查还没到上限, 应当留在队列里");
            assertEquals(at, row.getNextReviewAt(), "下一次复查时刻必须落在这一行上 —— 否则它下一分钟还在到期");
            assertEquals(1, row.getReviewCount());
            assertEquals(PendingMessageState.STATUS_PENDING, row.getStatus());
            assertEquals("忙", row.getReason());
            verify(repo).save(row);
        }

        /**
         * 上限那一条断言看的是<b>状态</b>而不只是返回值: 一个只返回 false 却把行
         * 留在 PENDING 的实现, 会让它下一分钟照样到期 —— "放下这件事"必须落库。
         */
        @Test
        @DisplayName("到上限 → 放下这件事(状态落库, 不只是返回 false)")
        void expiresAtLimit() {
            PendingMessageState row = pendingRow(PendingMessageService.MAX_REVIEWS - 1);
            when(repo.findById(PENDING_ID)).thenReturn(Optional.of(row));

            boolean kept = service.deferReview(PENDING_ID, LocalDateTime.now().plusHours(1), "还是忙", null);

            assertFalse(kept);
            assertEquals(PendingMessageService.MAX_REVIEWS, row.getReviewCount());
            assertEquals(PendingMessageState.STATUS_EXPIRED, row.getStatus());
            verify(repo).save(row);
        }

        @Test
        @DisplayName("摩擦类型只在给的时候才写 —— 不给不该把上一次的抹掉")
        void keepsFrictionWhenNotGiven() {
            PendingMessageState row = pendingRow(0);
            row.setFrictionType("SEEN_NO_REPLY");
            when(repo.findById(PENDING_ID)).thenReturn(Optional.of(row));

            service.deferReview(PENDING_ID, LocalDateTime.now().plusMinutes(30), "忙", null);

            assertEquals("SEEN_NO_REPLY", row.getFrictionType());
        }

        @Test
        @DisplayName("行已经不在了 → 不制造一条, 也不报错")
        void missingRowIsNotAnError() {
            when(repo.findById(PENDING_ID)).thenReturn(Optional.empty());

            assertFalse(service.deferReview(PENDING_ID, LocalDateTime.now().plusMinutes(30), "忙", null));
            verify(repo, never()).save(any());
        }

        /**
         * 已经回了 / 已经放下的行不该被"再延后一次"救活 —— 那会让一条已经了结的消息
         * 重新进入队列, 而她明明已经回过了(或已经忘了)。
         */
        @Test
        @DisplayName("已经结束的行(REPLIED/EXPIRED)不再被推后")
        void finishedRowStaysFinished() {
            PendingMessageState row = pendingRow(1);
            row.setStatus(PendingMessageState.STATUS_REPLIED);
            when(repo.findById(PENDING_ID)).thenReturn(Optional.of(row));

            assertFalse(service.deferReview(PENDING_ID, LocalDateTime.now().plusMinutes(30), "忙", null));
            assertEquals(PendingMessageState.STATUS_REPLIED, row.getStatus());
            verify(repo, never()).save(any());
        }
    }

    @Nested
    @DisplayName("postpone —— 她睡着了, 没想过这条消息")
    class Postpone {

        @Test
        @DisplayName("推时刻但**不**吃复查预算")
        void doesNotConsumeBudget() {
            PendingMessageState row = pendingRow(0);
            when(repo.findById(PENDING_ID)).thenReturn(Optional.of(row));
            LocalDateTime at = LocalDateTime.now().plusHours(1);

            service.postpone(PENDING_ID, at, "她睡着了");

            assertEquals(at, row.getNextReviewAt());
            assertEquals(0, row.getReviewCount(), "睡着不算她想过了 —— 否则睡三觉这条就被'放下'了");
            assertEquals(PendingMessageState.STATUS_PENDING, row.getStatus());
            assertEquals("她睡着了", row.getReason());
        }
    }

    @Nested
    @DisplayName("postponeIfOverdue —— 出错时的兜底, 但要认得出'已经推过了'")
    class PostponeIfOverdue {

        @Test
        @DisplayName("还到期的行 → 推后(否则毒行每分钟被捞一次)")
        void pushesWhenStillDue() {
            PendingMessageState row = pendingRow(0);
            when(repo.findById(PENDING_ID)).thenReturn(Optional.of(row));
            LocalDateTime at = LocalDateTime.now().plusHours(1);

            service.postponeIfOverdue(PENDING_ID, at, "复查出错, 稍后再试");

            assertEquals(at, row.getNextReviewAt());
            assertEquals(0, row.getReviewCount(), "系统没跑成, 不是她想过了 —— 不吃复查预算");
            verify(repo).save(row);
        }

        /**
         * 这一条是兜底**不能**无脑推后的理由: job 的 try 包住了整个 revaluate,
         * 所以"已经正常延后 30 分钟、然后在写日志时抛了"也会走到兜底里。
         * 无脑推后会把那个 30 分钟悄悄改成 1 小时 —— 变慢且没有痕迹。
         */
        @Test
        @DisplayName("已经推过、还没到点的行 → 一个字节都不改")
        void leavesAlreadyPostponedRowAlone() {
            PendingMessageState row = pendingRow(0);
            LocalDateTime already = LocalDateTime.now().plusMinutes(30);
            row.setNextReviewAt(already);
            when(repo.findById(PENDING_ID)).thenReturn(Optional.of(row));

            service.postponeIfOverdue(PENDING_ID, LocalDateTime.now().plusHours(1), "复查出错, 稍后再试");

            assertEquals(already, row.getNextReviewAt());
            verify(repo, never()).save(any());
        }

        @Test
        @DisplayName("已经结束的行 → 不改(不回队列, 也不复活)")
        void leavesFinishedRowAlone() {
            PendingMessageState row = pendingRow(0);
            row.setStatus(PendingMessageState.STATUS_EXPIRED);
            when(repo.findById(PENDING_ID)).thenReturn(Optional.of(row));

            service.postponeIfOverdue(PENDING_ID, LocalDateTime.now().plusHours(1), "复查出错, 稍后再试");

            assertEquals(PendingMessageState.STATUS_EXPIRED, row.getStatus());
            verify(repo, never()).save(any());
        }
    }

    @Nested
    @DisplayName("noteWantedToReply —— §54 想回忘了")
    class WantedToReply {

        @Test
        @DisplayName("记下摩擦类型、计数 +1、窗口推到三小时后")
        void recordsFrictionAndWidensWindow() {
            PendingMessageState row = pendingRow(0);
            when(repo.findById(PENDING_ID)).thenReturn(Optional.of(row));
            LocalDateTime before = LocalDateTime.now();

            assertTrue(service.noteWantedToReply(PENDING_ID));

            assertEquals("WANTED_TO_REPLY_FORGOT", row.getFrictionType());
            assertEquals(1, row.getReviewCount());
            assertNotNull(row.getNextReviewAt());
            assertTrue(row.getNextReviewAt().isAfter(before.plusHours(2)),
                    "人真的会忘, 所以这一类的窗口明显更长");
        }
    }
}
