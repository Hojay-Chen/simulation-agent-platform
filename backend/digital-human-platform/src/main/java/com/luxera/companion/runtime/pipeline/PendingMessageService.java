package com.luxera.companion.runtime.pipeline;

import com.luxera.companion.contracts.api.MessageView;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 未回复消息服务(§79/§81): "不回复"也是状态 —— 保存已读未回 + 下次复查时间。
 * 到点由 {@code PendingMessageReevaluationJob} 唤醒 Brain 重新评估。
 *
 * <h2>这张表自己是队列, 排期就是 {@code next_review_at} 这一列</h2>
 *
 * 复查的排期**只有一处**: 本表的 {@code next_review_at}。{@code PendingMessageReevaluationJob}
 * 每分钟扫的是它, 于是"把这次复查推后"这件事, 唯一的写法就是改这一列。
 *
 * <p>这条纪律是踩出来的: 曾经有两个"延后"分支把时间写进了<b>另一张表</b>
 * ({@code scheduled_actions}, 见已删的 {@code ScheduledActionService}), 而 {@code next_review_at}
 * 一个字都没动。后果不是"延后没生效"这么轻 —— 那些行<b>永远到期</b>, 于是同一批消息
 * 每分钟被重新捞起来复查一次, 永远不结束: 一个月里 {@code scheduled_actions} 攒下
 * 12 万行 FAILED, 日志每分钟刷十几行同样的"策略延后 30 分钟", 而 {@code review_count}
 * 是 0(因为没有任何一条路径去加它)。"延后"必须延后<b>这一列</b>。
 *
 * <h2>两次复查之间为什么有两个方法</h2>
 * {@link #postpone} 与 {@link #deferReview} 的差别只在一件事上: <b>这次算不算"她想过了"</b>。
 * 她睡着了不算(她没想过, 醒来还要看), 忙/被打断算(她想过并决定晚点回)。前者不吃复查
 * 预算, 后者吃 —— 而预算是那条让她最终能"放下这件事"的路, 见 {@link #MAX_REVIEWS}。
 */
@Service
public class PendingMessageService {

    /**
     * 复查上限: 复查这么多次她仍然没回, 就放下这件事(§79 "人偶尔会忘记")。
     *
     * <p>没有上限的复查队列等于一个永远不收敛的循环 —— 而它每一轮都会调一次模型。
     * 上限的作用不是省那几次调用, 是给"这条消息还在等"这件事一个<b>终点</b>:
     * 到点之后它是 EXPIRED 而不是 PENDING, 于是运维面看到的是"她忘了"而不是"她还在想"。
     */
    public static final int MAX_REVIEWS = 3;

    private final PendingMessageStateRepository repo;

    public PendingMessageService(PendingMessageStateRepository repo) {
        this.repo = repo;
    }

    /** 记录一条"已读但不回"的消息 */
    @Transactional
    public PendingMessageState defer(MessageView message, String companionId, String userId,
                                     String reason, LocalDateTime nextReviewAt) {
        return defer(message, companionId, userId, reason, nextReviewAt, "SEEN_NO_REPLY");
    }

    /**
     * 记录一条"已读但不回"的消息。
     * §54 Communication Friction: 摩擦类型标明"为什么不回" —— 看到了没回 / 想回忘了 / 回一半被打断。
     */
    @Transactional
    public PendingMessageState defer(MessageView message, String companionId, String userId,
                                     String reason, LocalDateTime nextReviewAt, String frictionType) {
        // 已有同消息记录 → 更新复查时间
        Optional<PendingMessageState> existing = repo.findByMessageIdAndStatus(message.getId(), PendingMessageState.STATUS_PENDING);
        if (existing.isPresent()) {
            PendingMessageState e = existing.get();
            e.setNextReviewAt(nextReviewAt);
            e.setReason(reason);
            e.setReadAt(LocalDateTime.now());
            if (frictionType != null) e.setFrictionType(frictionType);
            return repo.save(e);
        }
        PendingMessageState p = new PendingMessageState();
        p.setMessageId(message.getId());
        p.setCompanionId(companionId);
        p.setConversationId(message.getConversationId());
        p.setUserId(userId);
        p.setSenderText(message.getContent());
        p.setRead(true);
        p.setReadAt(LocalDateTime.now());
        p.setNextReviewAt(nextReviewAt);
        p.setReason(reason);
        p.setFrictionType(frictionType != null ? frictionType : "SEEN_NO_REPLY");
        return repo.save(p);
    }

    /**
     * §54: 记录"想回复但忘了"的摩擦 —— 复查时 Brain 想过要回但又被别的事打断。
     * 这类消息值得更长的复查窗口(人真的会忘), 由复查 Job 调用。
     *
     * @return true 表示这条消息还留着; false 表示复查预算已用尽, 已放下(EXPIRED)
     */
    @Transactional
    public boolean noteWantedToReply(String pendingMessageId) {
        return deferReview(pendingMessageId, LocalDateTime.now().plusHours(3),
                null, "WANTED_TO_REPLY_FORGOT");
    }

    /**
     * 把复查推后, 并且<b>不吃</b>复查预算 —— 她睡着了, 没想过这条消息。
     *
     * <p>与 {@link #deferReview} 的分工见类注释。睡着时用这个: 否则她睡三觉之后
     * 这条消息就被"放下"了, 而她其实还没看过。
     */
    @Transactional
    public void postpone(String pendingMessageId, LocalDateTime nextReviewAt, String reason) {
        repo.findById(pendingMessageId).ifPresent(p -> {
            p.setNextReviewAt(nextReviewAt);
            if (reason != null) p.setReason(reason);
            repo.save(p);
        });
    }

    /**
     * 出错了也要把这一行推后 —— <b>但只在它真的还到期的时候</b>。
     *
     * <p>这是 {@code PendingMessageReevaluationJob} 的兜底: 那一轮里抛异常的那几条,
     * 时刻一个字都没动, 于是下一分钟它们还在原地到期。<b>一条会抛异常的毒行会被
     * 每分钟捞起来一次, 永远</b> —— 而每次都要过一次向量化与一次模型调用(在
     * provider 挂掉的那段时间里, 那就是全表每 60 秒齐刷刷失败一遍)。
     *
     * <p>为什么不能无脑推后: job 的 try 块<b>包住了整个 revaluate</b>, 于是"已经正常
     * 延后 30 分钟、然后在写日志时抛了"这种情况也会走到兜底里。那时无脑推后会把一个
     * 30 分钟的延后悄悄改成 1 小时 —— 一个只会让行为变慢、且完全没有痕迹的改动。
     * 所以这里先读一次行, 只在它<b>仍处于到期状态</b>时才推。
     *
     * <p>刻意用 {@link #postpone} 的语义(不吃复查预算): 这一条是"系统没跑成",
     * 不是"她想过了"。
     */
    @Transactional
    public void postponeIfOverdue(String pendingMessageId, LocalDateTime nextReviewAt, String reason) {
        repo.findById(pendingMessageId).ifPresent(p -> {
            if (!PendingMessageState.STATUS_PENDING.equals(p.getStatus())) return;
            LocalDateTime due = p.getNextReviewAt();
            if (due != null && !due.isBefore(LocalDateTime.now())) return;
            p.setNextReviewAt(nextReviewAt);
            if (reason != null) p.setReason(reason);
            repo.save(p);
        });
    }

    /**
     * 一次真正的复查: 把时刻推后、计数 +1, 到 {@link #MAX_REVIEWS} 就放下。
     *
     * <p>方法名刻意不叫 {@code defer} —— {@link #defer(MessageView, String, String, String, LocalDateTime)}
     * 是"她决定不回时**建档**", 这个是"已经建过档的再复查一次"。两件事的调用点不同,
     * 混用一个名字会让"为什么这里要传 MessageView 而那里传 id"变成一个每次都要重推的问题。
     *
     * @return true 表示还留着(未到上限); false 表示已到上限并标记 EXPIRED
     */
    @Transactional
    public boolean deferReview(String pendingMessageId, LocalDateTime nextReviewAt, String reason,
                               String frictionType) {
        Optional<PendingMessageState> found = repo.findById(pendingMessageId);
        if (found.isEmpty()) {
            // 行没了(被退休清理、或消息其实已经回了) —— 没有可推的东西, 不制造一条
            return false;
        }
        PendingMessageState p = found.get();
        if (!PendingMessageState.STATUS_PENDING.equals(p.getStatus())) {
            return false;
        }
        int reviews = p.getReviewCount() + 1;
        if (reason != null) p.setReason(reason);
        if (frictionType != null) p.setFrictionType(frictionType);
        p.setReviewCount(reviews);
        if (reviews >= MAX_REVIEWS) {
            // 她想过 MAX_REVIEWS 次都没回。这不是"还在等", 是"她忘了" —— 到一个终点上,
            // 不然这一行会把她余生里每一次心跳都变成一次模型调用。
            p.setStatus(PendingMessageState.STATUS_EXPIRED);
            repo.save(p);
            return false;
        }
        p.setNextReviewAt(nextReviewAt);
        repo.save(p);
        return true;
    }

    /** 到期的待复查消息(已读未回, 到复查点) */
    @Transactional(readOnly = true)
    public List<PendingMessageState> dueForReview(LocalDateTime now) {
        return repo.findByStatusAndNextReviewAtLessThanEqualOrderByNextReviewAtAsc(
                PendingMessageState.STATUS_PENDING, now);
    }

    @Transactional(readOnly = true)
    public List<PendingMessageState> pendingFor(String companionId) {
        return repo.findByCompanionIdAndStatus(companionId, PendingMessageState.STATUS_PENDING);
    }

    /** 标记已回复(消息回复后调用) */
    @Transactional
    public void markReplied(String messageId) {
        repo.findByMessageIdAndStatus(messageId, PendingMessageState.STATUS_PENDING).ifPresent(p -> {
            p.setReplied(true);
            p.setStatus(PendingMessageState.STATUS_REPLIED);
            repo.save(p);
        });
    }

    /** 标记过期(复查多次后仍决定不回 → 放下这件事, 符合"人偶尔忘记") */
    @Transactional
    public void markExpired(String messageId) {
        repo.findByMessageIdAndStatus(messageId, PendingMessageState.STATUS_PENDING).ifPresent(p -> {
            p.setStatus(PendingMessageState.STATUS_EXPIRED);
            repo.save(p);
        });
    }

    @Transactional(readOnly = true)
    public Optional<PendingMessageState> findByMessageId(String messageId) {
        return repo.findByMessageId(messageId);
    }
}
