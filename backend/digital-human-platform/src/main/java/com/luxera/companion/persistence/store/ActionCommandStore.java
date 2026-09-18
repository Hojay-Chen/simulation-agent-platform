package com.luxera.companion.persistence.store;

import com.luxera.companion.boundary.action.ActionCommand;
import com.luxera.companion.boundary.action.ActionResult;
import com.luxera.companion.persistence.entity.ActionCommandRecord;
import com.luxera.companion.persistence.repository.ActionCommandRecordRepository;
import lombok.extern.slf4j.Slf4j;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * V2.2 §7.2 —— {@code action_command}: <b>"她做过什么"的账本, 以及幂等的唯一落点</b>。
 *
 * <h2>为什么这个动作需要一张表</h2>
 * 她"发一条消息"是一次跨越进程边界的副作用。跨界的动作有三个必须落库的时刻:
 * <ol>
 *   <li><b>发出之前</b>要留一条 {@code PENDING} —— 否则崩溃之后没有人知道
 *       "那条消息到底发出去了没有";</li>
 *   <li><b>发出之后</b>要记下 {@code RUNNING} 与幂等键 —— 重试必须带着<b>同一个键</b>,
 *       否则 {@code ActionCommand} 的类注释里那段"她连发两条一样的"就会发生;</li>
 *   <li><b>拿到结果之后</b>要写结局 —— 而结局的形状不是"成功/失败"两态:
 *       {@code UNAVAILABLE}（待会儿再试）/ {@code REJECTED}（换个做法）/
 *       {@code FAILED}（记下来等修）是完全不同的三种反应（见 {@code ActionResult} 的论证）。
 *       压成一个布尔, 决策层就没有可用的信息了。</li>
 * </ol>
 *
 * <h2>状态列为什么是 {@code String} 而不是 {@code @Enumerated}</h2>
 * 见 {@code ActionCommandRecord.getStatus()} 的说明, 一句话:
 * <b>{@code @Enumerated(EnumType.STRING)} 让"删掉一个枚举常量"变成"所有历史行读不回来"</b>。
 * 这里的取值是历史的一部分, 不是当前代码的一部分。
 *
 * <h2>写入路径上唯一不允许的事: 改一条已经完成的命令</h2>
 * {@link #complete} 对已经完成的命令<b>什么都不做</b>并记一条 WARN。
 * 理由不是洁癖, 而是一个具体的场景: 重试路径可能在超时后重新跑一遍,
 * 而那时第一次调用的结果刚刚写进去 —— 覆盖它的后果是
 * "她发了两次消息, 而库里只有一条记录说是两次重试的一次"。
 * 结局一旦写下就是事实, 事实不改。
 */
@Slf4j
public class ActionCommandStore {

    /**
     * 还没跑完的状态。
     *
     * <p>{@code PENDING} 是"还没发出去", {@code RUNNING} 是"发出去了但没等到结果"。
     * 两者的区别很重要: 前者可以安全地重发, 后者必须<b>带着原来的幂等键去问执行侧</b>
     * （见 {@code ActionCommandRecordRepository#findByHumanIdAndStatusInOrderByIssuedAtAsc}）。
     */
    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_RUNNING = "RUNNING";

    /** 命令被撤销 —— 她改主意了, 而这件事发生在发出之前。 */
    public static final String STATUS_CANCELLED = "CANCELLED";

    private final ActionCommandRecordRepository repository;

    public ActionCommandStore(ActionCommandRecordRepository repository) {
        this.repository = Objects.requireNonNull(repository, "命令仓库不能为空");
    }

    // ─────────────────────────── 写 ───────────────────────────

    /**
     * 打算做一件事 —— <b>在动手之前记下来</b>。
     *
     * <p>顺序是刻意的。先执行再记账的版本有一个无法弥补的窗口:
     * 消息发出去了、库里没有记录, 于是重试时会再发一条
     * —— 而那一秒的崩溃恰好发生在"她最不想重复"的操作上。
     */
    public ActionCommandRecord issue(String humanId, ActionCommand command) {
        Objects.requireNonNull(humanId, "命令必须属于某个人");
        Objects.requireNonNull(command, "要落库的命令不能为空");

        ActionCommandRecord record = new ActionCommandRecord();
        record.setId(command.commandId());
        record.setHumanId(humanId);
        record.setCapabilityKey(command.capabilityKey());
        record.setArgumentsJson(command.arguments());
        record.setStatus(STATUS_PENDING);
        record.setIdempotencyKey(command.idempotencyKey());
        record.setIssuedAt(command.issuedAt());
        return repository.save(record);
    }

    /** 发出去了, 在等结果 —— {@code PENDING → RUNNING}。 */
    public ActionCommandRecord markRunning(ActionCommandRecord record) {
        Objects.requireNonNull(record, "要更新的那一行不能为空");
        if (!STATUS_PENDING.equals(record.getStatus())) {
            log.warn("[Persistence] 命令 {} 的状态是 {}, 不是 {} —— 不改成 RUNNING。"
                            + "一个已经完成的命令不该被重新标成'在跑'",
                    record.getId(), record.getStatus(), STATUS_PENDING);
            return record;
        }
        record.setStatus(STATUS_RUNNING);
        return repository.save(record);
    }

    /**
     * 拿到结局了。
     *
     * <p>四个终态直接取自 {@code ActionResult.Status} 的 {@code name()} ——
     * <b>刻意不另起一套字符串</b>: 两套名字之间迟早会出现一个不对应的取值,
     * 而那个取值会表现为"她的某类失败在库里查不到"。
     *
     * @return 更新后的行。已经完成的行原样返回（见类注释末段）
     */
    public ActionCommandRecord complete(ActionCommandRecord record, ActionResult result) {
        Objects.requireNonNull(record, "要更新的那一行不能为空");
        Objects.requireNonNull(result, "结局不能为空 —— 它是这条命令为什么停在这里的答案");

        if (record.completed()) {
            log.warn("[Persistence] 命令 {} 已经以 {} 结束了, 现在又收到一个 {} 的结局 —— "
                            + "保留第一次的。结局一旦写下就是事实: 覆盖它会让"
                            + "'她发了两次消息'这类重复副作用在库里只剩一条记录",
                    record.getId(), record.getStatus(), result.status());
            return record;
        }

        record.setStatus(result.status().name());
        record.setCompletedAt(result.completedAt());
        record.setOutcomeNote(result.reason().orElse(null));
        record.setResultJson(result.data());
        return repository.save(record);
    }

    /** 她改主意了（还没发出去）。 */
    public ActionCommandRecord cancel(ActionCommandRecord record, String why) {
        Objects.requireNonNull(record, "要更新的那一行不能为空");
        if (record.completed()) {
            log.warn("[Persistence] 命令 {} 已经以 {} 结束了, 不能撤销", record.getId(),
                    record.getStatus());
            return record;
        }
        record.setStatus(STATUS_CANCELLED);
        record.setOutcomeNote(why == null || why.isBlank() ? "她改主意了" : why);
        return repository.save(record);
    }

    // ─────────────────────────── 读 ───────────────────────────

    /**
     * 这个幂等键做过吗 —— <b>它不是保证, 只是一句好一点的错误消息</b>。
     *
     * <p>真正的保证是数据库上的唯一约束
     * {@code uk_action_command_idempotency}。这个方法服务于
     * "重试之前先问一句", 而它与约束之间必然有一个时间窗口 ——
     * 两个进程同时问、都得到"没做过"、然后都发出去。这个窗口<b>存在且不可避免</b>,
     * 所以"不重复"这件事由约束在写入点兜住, 而不是由这个方法兜住。
     * 把这一点写下来, 是因为一个被误当成保证的检查会让下一个人删掉那条约束。
     *
     * <p>{@code null} 的幂等键（"看一眼手机"这类重复无害的命令）返回
     * {@code Optional.empty()} —— 与"查了但没查到"是同一个返回, 而这一次是<b>对的</b>:
     * 一个没有键的命令在语义上就是"它没有'做过'这个概念"。
     */
    public Optional<ActionCommandRecord> findByIdempotencyKey(String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            return Optional.empty();
        }
        return repository.findByIdempotencyKey(idempotencyKey);
    }

    /**
     * 崩溃恢复: 还没跑完的命令, 正序。
     *
     * <p>恢复流程拿到它之后<b>不该重发</b>, 而该带着幂等键去问执行侧
     * "这个键的结果是什么" —— 见 {@link #STATUS_PENDING} 的说明。
     */
    public List<ActionCommandRecord> unfinished(String humanId) {
        Objects.requireNonNull(humanId, "必须指明是哪个人");
        return repository.findByHumanIdAndStatusInOrderByIssuedAtAsc(humanId,
                List.of(STATUS_PENDING, STATUS_RUNNING));
    }

    /** "她今天做过什么" —— 时间轴视图。 */
    public List<ActionCommandRecord> recent(String humanId) {
        Objects.requireNonNull(humanId, "必须指明是哪个人");
        return repository.findTop200ByHumanIdOrderByIssuedAtDesc(humanId);
    }

    // ─────────────────────────── 工具 ───────────────────────────

    /**
     * 这批命令里有没有"同一个幂等键被写过两次"。
     *
     * <p>它<b>不该</b>在正常情况下返回非空 —— 唯一约束会先挡住。
     * 但唯一约束在 {@code ddl-auto: update} 之外的地方可能没建上
     * （手工建的表、从旧库导入的数据）, 而这个方法的输出是一个可以直接
     * 拿去告警的清单: <b>重复发出的那些消息</b>。
     * 一次"她连发两条一样的"如果没有被发现, 就永远不会被修 ——
     * 因为它的表现只是"她今天有点固执"。
     */
    public List<String> duplicateIdempotencyKeys(List<ActionCommandRecord> rows) {
        Objects.requireNonNull(rows, "要检查的行不能为空");
        java.util.Set<String> seen = new java.util.HashSet<>();
        List<String> duplicates = new ArrayList<>();
        for (ActionCommandRecord row : rows) {
            String key = row.getIdempotencyKey();
            if (key == null || key.isBlank()) {
                continue;
            }
            if (!seen.add(key) && !duplicates.contains(key)) {
                duplicates.add(key);
            }
        }
        return List.copyOf(duplicates);
    }

    /** 诊断: 一条命令的来龙去脉。 */
    public static String describe(ActionCommandRecord record) {
        Objects.requireNonNull(record, "要描述的行不能为空");
        return record.getHumanId() + " " + record.getCapabilityKey()
                + " [" + record.getId() + "] " + record.getStatus()
                + " @ " + record.getIssuedAt()
                + (record.getCompletedAt() == null ? "" : "→" + record.getCompletedAt())
                + (record.getIdempotencyKey() == null ? ""
                : " 幂等键=" + record.getIdempotencyKey());
    }

    /**
     * 超时未决的命令 —— "发出去了, 但等了太久还没有结果"。
     *
     * <p>本层不判断"多久算太久": 那个阈值属于执行策略, 由调用方给。
     * 这里只做一次纯粹的比较 —— 这也是本层不读时钟的另一种体现:
     * {@code now} 是参数, 不是 {@code Instant.now()}。
     */
    public List<ActionCommandRecord> overdue(List<ActionCommandRecord> rows, Instant now,
                                             java.time.Duration threshold) {
        Objects.requireNonNull(rows, "要检查的行不能为空");
        Objects.requireNonNull(now, "必须给出'现在' —— 本层不读时钟");
        Objects.requireNonNull(threshold, "阈值不能为空");
        List<ActionCommandRecord> out = new ArrayList<>();
        for (ActionCommandRecord row : rows) {
            if (!row.completed() && row.getIssuedAt().plus(threshold).isBefore(now)) {
                out.add(row);
            }
        }
        return List.copyOf(out);
    }
}
