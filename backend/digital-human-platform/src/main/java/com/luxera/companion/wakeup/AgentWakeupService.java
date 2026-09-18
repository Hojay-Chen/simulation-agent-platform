package com.luxera.companion.wakeup;

import com.luxera.companion.world.AgentEventType;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;

/**
 * 设计文档 §18.1 —— <b>她下一次什么时候醒</b>。
 *
 * <h2>这里只有"排期", 没有"判断"</h2>
 * 本类不回答"她该不该醒"(那是决策与触发器的活), 只负责把"某个时刻因为某个理由醒来"
 * 这件事<b>记住并去重</b>。判断与排期分开的理由和 {@code MindDecisionPlanner} 一样:
 * 一个会查库的"她该不该醒"只能靠在集成测试里碰运气, 而一个纯粹的 upsert 可以被穷举。
 *
 * <h2>过去的时刻不是错误</h2>
 * {@link #schedule} 收到一个已经过去的时刻时, 不拒绝、不报错, 而是<b>夹到现在</b>。
 * 理由: "她早该醒了"是一个真实且常见的情况(进程停了、她当时被暂停、对方几分钟前就该收到回复),
 * 而拒绝它的后果是那个闹钟永远不会响 —— 表现为"她再也没提过这件事",
 * 排查时看不出任何错误。夹到现在则等于"下一轮就醒", 这是唯一不会静默丢事的处理。
 */
@Service
@Slf4j
public class AgentWakeupService {

    /** 理由列宽 160。超了不是截断得难看, 是 INSERT 直接失败。 */
    static final int REASON_MAX = 160;
    /** 来源键列宽 128。 */
    static final int KEY_MAX = 128;

    /** 来源键前缀 —— 常量集中在这里, 免得同一个来源在两处写成两种拼法。 */
    public static final String SRC_INTENTION = "intention";
    public static final String SRC_OPEN_LOOP = "openloop";
    public static final String SRC_SILENCE = "silence";
    public static final String SRC_LIFE = "life";
    public static final String SRC_DECISION = "decision";

    private final AgentWakeupRepository repo;

    public AgentWakeupService(AgentWakeupRepository repo) {
        this.repo = repo;
    }

    /**
     * 排一个闹钟。<b>幂等</b>: 同一个 {@code (agentId, type, sourceKey)} 只会有一行。
     *
     * <p>已经存在且还在等的那一个会被<b>改时刻</b>而不是新增 —— 她本来说"一小时后",
     * 又说"算了, 三小时后", 那是同一个闹钟被推后了。重复排期如果各占一行,
     * 到点时会一起响, 于是"她醒来"在日志里变成一串同时到达的事件。
     *
     * @return true 表示这次调用真的改动了什么(新增或改时刻)
     */
    @Transactional
    public boolean schedule(String agentId, LocalDateTime wakeAt, AgentEventType type,
                            String sourceKey, String reason) {
        if (agentId == null || type == null || sourceKey == null || sourceKey.isBlank()) {
            return false;
        }
        if (wakeAt == null) {
            // 没有时刻的闹钟是"以后再想" —— 那不该占一行, 因为它永远不会响
            return false;
        }
        String key = truncate(sourceKey.trim(), KEY_MAX);
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime at = wakeAt.isBefore(now) ? now : wakeAt;

        AgentWakeup existing = repo
                .findByAgentIdAndEventTypeAndSourceKey(agentId, type, key)
                .orElse(null);
        if (existing == null) {
            AgentWakeup w = new AgentWakeup();
            w.setAgentId(agentId);
            w.setEventType(type);
            w.setSourceKey(key);
            w.setWakeAt(at);
            w.setReason(truncate(reason, REASON_MAX));
            w.setStatus(AgentWakeup.S_PENDING);
            repo.save(w);
            log.debug("[Wakeup] {} 排了闹钟 {} → {} ({})", agentId, type.wire(), at, reason);
            return true;
        }
        if (existing.isPending() && existing.getWakeAt().equals(at)) {
            // 同一件事、同一个时刻 —— 一次真正的空转。返回 false 让调用方能区分
            // "我改了主意"与"我又说了一遍同样的话"。
            return false;
        }
        // 已经响过的闹钟被重新排期是合法的: 她等的事又有了新的时刻
        // (面试结果没等到, 又约了下一次)。所以这里不新建行, 而是复活它 ——
        // 否则 FIRED 的行会一直留着 sourceKey, 而新行会因为幂等键撞上它。
        existing.setWakeAt(at);
        existing.setReason(truncate(reason, REASON_MAX));
        existing.setStatus(AgentWakeup.S_PENDING);
        existing.setFiredAt(null);
        repo.save(existing);
        log.debug("[Wakeup] {} 改了闹钟 {} → {} ({})", agentId, type.wire(), at, reason);
        return true;
    }

    /** 到点了、还在等的闹钟, 按时刻升序。 */
    @Transactional(readOnly = true)
    public List<AgentWakeup> due(LocalDateTime now, int limit) {
        if (now == null || limit <= 0) {
            return List.of();
        }
        return repo.findByStatusAndWakeAtBeforeOrderByWakeAtAsc(
                AgentWakeup.S_PENDING, now, PageRequest.of(0, limit));
    }

    /** 事件已经进了信箱 —— 从这一刻起这行只是历史。 */
    @Transactional
    public void markFired(AgentWakeup wakeup, LocalDateTime now) {
        if (wakeup == null) {
            return;
        }
        wakeup.setStatus(AgentWakeup.S_FIRED);
        wakeup.setFiredAt(now == null ? LocalDateTime.now() : now);
        repo.save(wakeup);
    }

    /**
     * 她不再等这件事了(对方回了、意图作废)。
     *
     * @return 被撤掉的闹钟数
     */
    @Transactional
    public int cancelFor(String agentId, String sourceKey) {
        if (agentId == null || sourceKey == null) {
            return 0;
        }
        List<AgentWakeup> pending = repo.findByAgentIdAndSourceKeyAndStatus(
                agentId, truncate(sourceKey.trim(), KEY_MAX), AgentWakeup.S_PENDING);
        LocalDateTime now = LocalDateTime.now();
        for (AgentWakeup w : pending) {
            w.setStatus(AgentWakeup.S_CANCELLED);
            w.setFiredAt(now);
        }
        if (!pending.isEmpty()) {
            repo.saveAll(pending);
            log.debug("[Wakeup] {} 撤掉了 {} 个闹钟 ({})", agentId, pending.size(), sourceKey);
        }
        return pending.size();
    }

    /**
     * 她下一次什么时候醒。
     *
     * <p>取<b>最早</b>的那个, 而不是"最后排的那个" —— 一行一个闹钟的代价就在这里,
     * 而调用方自己去比大小迟早会有人比错方向。
     */
    @Transactional(readOnly = true)
    public LocalDateTime nextWakeupOf(String agentId) {
        if (agentId == null) {
            return null;
        }
        return repo.findFirstByAgentIdAndStatusOrderByWakeAtAsc(agentId, AgentWakeup.S_PENDING)
                .map(AgentWakeup::getWakeAt)
                .orElse(null);
    }

    /** 她名下还等着的全部闹钟。诊断用。 */
    @Transactional(readOnly = true)
    public List<AgentWakeup> pendingOf(String agentId) {
        if (agentId == null) {
            return List.of();
        }
        return repo.findByAgentIdAndStatusOrderByWakeAtAsc(agentId, AgentWakeup.S_PENDING);
    }

    /**
     * 清掉早就响过/撤过的历史行。
     *
     * <p>闹钟是<b>事件</b>, 不是状态: 它响过之后除了"她曾经在等什么"之外没有别的用处,
     * 而那个用处留在日志里就够了。不清的话, 一个活跃的 agent 每天会攒下几十行,
     * 而 {@link #due} 的查询会一直在变大的表上做 —— 它跑在调度线程上。
     */
    @Transactional
    public int purgeFinishedBefore(LocalDateTime cutoff) {
        if (cutoff == null) {
            return 0;
        }
        return (int) repo.deleteByStatusInAndFiredAtBefore(
                List.of(AgentWakeup.S_FIRED, AgentWakeup.S_CANCELLED), cutoff);
    }

    public long pendingCount() {
        return repo.countByStatus(AgentWakeup.S_PENDING);
    }

    // ─────────────────── 来源键的拼法(一处) ───────────────────

    /** {@code intention:<id>}。id 为空时返回 null —— 让调用方的 schedule 直接落空, 别排一个"无来源"的闹钟。 */
    public static String key(String prefix, String id) {
        if (prefix == null || id == null || id.isBlank()) {
            return null;
        }
        return truncate(prefix.toLowerCase(Locale.ROOT) + ":" + id, KEY_MAX);
    }

    static String truncate(String raw, int max) {
        if (raw == null) {
            return null;
        }
        String t = raw.trim();
        return t.length() <= max ? t : t.substring(0, max);
    }
}
