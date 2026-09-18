package com.luxera.companion.persistence.repository;

import com.luxera.companion.persistence.entity.ActionCommandRecord;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * V2.2 §7.2 —— {@code action_command} 的读口。
 *
 * <h2>{@link #findByIdempotencyKey} 不是幂等的保证, 别把它当保证用</h2>
 * 这个方法存在, 但它的职责<b>只有一个</b>: 让正常路径返回一句人话
 * （"这条命令刚才已经发过了"）, 而不是让调用方收到一个
 * {@code DataIntegrityViolationException} 的翻译结果。
 *
 * <p>真正的保证在 {@link ActionCommandRecord} 的唯一约束上
 * （{@code uk_action_command_idempotency}）—— 与 {@code person/Person.handle}
 * 那一处的推理完全相同（见 {@code PersonRepository.findByHandle} 的 javadoc:
 * "**不是**唯一性的保证, 这里查一次只是为了让正常路径返回一句人话"）。
 * 两者并存的理由是: 先查再写之间永远有一个窗口, 而关掉那个窗口的唯一办法是
 * 让数据库裁决 —— 代码里那次查重的工作是<b>改善错误信息</b>, 不是保证正确性。
 *
 * <h2>为什么要 {@code Optional} 而不是 {@code List}</h2>
 * 唯一约束保证同键最多一行, 于是 {@code Optional} 是准确的类型。
 * 若哪天这里抛了 {@code IncorrectResultSizeDataAccessException},
 * 那说明唯一约束<b>没有生效</b>（例如表是手建的）—— 那是一个必须被看见的故障,
 * 而不是一个可以取第一条了事的场景。
 */
public interface ActionCommandRecordRepository extends JpaRepository<ActionCommandRecord, String> {

    /**
     * 幂等键查重 —— 见类注释。走 {@code uk_action_command_idempotency}。
     *
     * <p>注意 {@code null} 的语义: 无幂等键的命令（"看一眼手机"）在库里是
     * {@code NULL}, 而 SQL 的唯一约束与等值查询都不把 {@code NULL} 当作一个值
     * —— 所以这个方法对 {@code null} 入参的行为是"查不到任何东西"。
     * 调用方因此<b>必须</b>先判空再查, 而不是指望它返回一个合理的答案。
     */
    Optional<ActionCommandRecord> findByIdempotencyKey(String idempotencyKey);

    /** "她今天做过什么" —— 走 {@code idx_action_command_human_time}。倒序, 人看最近的。 */
    List<ActionCommandRecord> findTop200ByHumanIdOrderByIssuedAtDesc(String humanId);

    /** 她这一段时间的命令, 正序 —— 行为分析要读的是流程, 不是最新的那几条。 */
    List<ActionCommandRecord> findByHumanIdAndIssuedAtAfterOrderByIssuedAtAsc(
            String humanId, java.time.Instant after);

    /**
     * 崩溃恢复: 还没跑完的命令 —— 走 {@code idx_action_command_human_status}。
     *
     * <p>为什么恢复要看它们: 这批命令处于"不知道自己有没有生效"的状态
     * （{@code PENDING} 是还没发出去, {@code RUNNING} 是发出去了但没等到结果）。
     * 对它们的正确处理<b>不是重发</b>, 而是带着<b>原来的幂等键</b>去询问执行侧
     * "这个键的结果是什么" —— 这正是 {@code ActionCommand} 的类注释所说的
     * "重试必须保证她不会连发两条一样的"。
     *
     * <p>用 {@code In} 而不是两次单值查询: 恢复要一次拿到这两种状态,
     * 而逐次查询会让一次恢复变成两次往返 —— 更重要的是, 两次调用之间
     * 状态可能变（她的一条命令跑完了）, 于是两次查询的结果不是同一个快照。
     */
    List<ActionCommandRecord> findByHumanIdAndStatusInOrderByIssuedAtAsc(
            String humanId, Collection<String> statuses);

    /** "这个能力被调用过几次" —— 走 {@code idx_action_command_capability}。 */
    List<ActionCommandRecord> findTop100ByCapabilityKeyOrderByIssuedAtDesc(String capabilityKey);

    /** 计数: 她这个能力的调用次数 —— 能力健康度用。 */
    long countByHumanIdAndCapabilityKey(String humanId, String capabilityKey);

    /** 按状态计数 —— "她今天失败了几次"。 */
    long countByHumanIdAndStatus(String humanId, String status);
}
