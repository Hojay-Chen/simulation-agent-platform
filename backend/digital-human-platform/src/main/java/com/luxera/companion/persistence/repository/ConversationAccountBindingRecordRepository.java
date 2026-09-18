package com.luxera.companion.persistence.repository;

import com.luxera.companion.persistence.entity.ConversationAccountBindingRecord;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * V2.2 §7.2 / §3.4.6 —— {@code conversation_account_binding} 的读口。
 *
 * <h2>{@link #findFirstByHumanIdAndChatAccountIdOrderByBoundAtDesc} 是本表最热的一次查询</h2>
 * 每收到一条消息都要问一次"这个账号是谁"。它必须是一次索引查询
 * （走 {@code idx_binding_human_account}）, 而不是"把她的通讯录整个读进内存再找"——
 * 后者在"她认识的人多起来"之后会变成每条消息一次全量读。
 *
 * <h2>为什么是 {@code findFirst...OrderyByBoundAtDesc} 而不是 {@code Optional}</h2>
 * 见 {@link ConversationAccountBindingRecord#getId()} 的说明: 这张表<b>没有唯一约束</b>,
 * 因为 {@code bind} 的语义是"她改主意了, 把这个人重新归到另一个人物对象上",
 * 而那个改主意<b>应该</b>留下历史（§3.4.6: 一个账号只对应一个人是<b>值</b>的约束,
 * 不是<b>行数</b>的约束）。
 *
 * <p>于是"这个账号是谁"的正确读法是<b>取最新的一条绑定</b>
 * （{@code bound_at} 最大）——"她最近一次认为这个人是谁"。
 * 这也让"她改过主意"这件事本身成了可查的事实:
 * 见 {@link #findByHumanIdAndChatAccountIdOrderByBoundAtAsc}, 它能回答
 * "她一开始以为这是谁, 后来改成了谁"。
 */
public interface ConversationAccountBindingRecordRepository
        extends JpaRepository<ConversationAccountBindingRecord, String> {

    /**
     * 热路径: "这个账号是谁" —— 走 {@code idx_binding_human_account}, 取最新一条。
     *
     * <p>{@code Optional.empty()} 是<b>正常返回值, 不是错误</b>:
     * 一个陌生账号意味着"有个不认识的人找我"（§3.4.6）, 而那是需要被表达的事实。
     * 调用方（{@code RelationshipGraph.resolve}）的签名就是 {@code Optional}。
     */
    Optional<ConversationAccountBindingRecord> findFirstByHumanIdAndChatAccountIdOrderByBoundAtDesc(
            String humanId, String chatAccountId);

    /**
     * 她的整本通讯录 —— 走 {@code idx_binding_human_account} 的前缀。
     *
     * <p>正序（最早绑定的在前）: 通讯录的自然读法是"她先认识了谁、后来又认识了谁"。
     */
    List<ConversationAccountBindingRecord> findByHumanIdOrderByBoundAtAsc(String humanId);

    /**
     * 她改过主意吗 —— 同一个账号的全部绑定历史, 正序。
     *
     * <p>行为分析用: "她一开始把这个账号当成陌生人, 后来认识成了同学"是一段
     * 值得读的故事, 而它的存储形态就是这张表里的两行。
     */
    List<ConversationAccountBindingRecord> findByHumanIdAndChatAccountIdOrderByBoundAtAsc(
            String humanId, String chatAccountId);

    /**
     * 反向: "这条绑定指向的人还在吗 / 哪些账号绑到了同一个人".
     *
     * <p>走 {@code idx_binding_person}。注意 {@code person_id} <b>没有外键约束</b>
     * （见 {@link ConversationAccountBindingRecord} 的类注释: 加外键会让"她忘了一个人"
     * 这个动作失败）—— 所以这个方法返回的行里, {@code person_id} 指向的
     * {@code person_object} 可能已经不存在了。调用方要按
     * "查不到就当作不认识"处理, 而不是当作数据损坏。
     */
    List<ConversationAccountBindingRecord> findByHumanIdAndPersonIdOrderByBoundAtAsc(
            String humanId, String personId);

    /** 她通讯录里有几条绑定。 */
    long countByHumanId(String humanId);

    /** 她预置了几条绑定（{@code BOOTSTRAP} 那一档）—— 新建 agent 之后的健康检查。 */
    long countByHumanIdAndBindReason(String humanId, String bindReason);
}
