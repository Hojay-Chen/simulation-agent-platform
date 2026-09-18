package com.luxera.companion.persistence.repository;

import com.luxera.companion.persistence.entity.AgentOwnershipRecord;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * V2.2 §3.6 / §7.2 —— {@code agent_ownership} 的读口: <b>"她归谁、跑不跑"</b>。
 *
 * <h2>本接口上最要紧的一件事: 没有 {@code findByHumanId} 之外的"按归属找"</h2>
 * 这张表支持两类查询, 它们的形状完全不同:
 * <table border="1">
 *   <tr><th>问</th><th>方法</th><th>走哪条索引</th></tr>
 *   <tr><td>"这个 agent 归谁"（热路径: 每次 tick 之前判 {@code PAUSED}）</td>
 *       <td>{@link #findByHumanId(String)}</td>
 *       <td>{@code uk_agent_ownership_human}（唯一约束隐含的索引）</td></tr>
 *   <tr><td>"这个用户名下有哪些 agent"（控制台列表 / 配额计数）</td>
 *       <td>{@link #findByOwnerUserIdOrderByCreatedAtAsc(String)}</td>
 *       <td>{@code idx_agent_ownership_owner}</td></tr>
 * </table>
 * 两条索引都不能省。第一条尤其容易被人误以为"唯一约束顺便解决了"——
 * 唯一约束建在 {@code human_id} 上, 而第二类查询的第一列是 {@code owner_user_id},
 * 用不上它。
 *
 * <h2>{@code findByHumanId} 返回 {@code Optional}, 这是语义而不是习惯</h2>
 * "查不到"有两种可能, 而<b>两种都应该被表达, 而不是抛异常</b>:
 * <ol>
 *   <li>这不是一个 agent（id 拼错了、或者是别的表的主键）——
 *       运维排查时第一句话就是 {@code SELECT ... WHERE human_id = ?},
 *       查不到本身就是一个答案;</li>
 *   <li>这一行<b>被删除过</b> —— 而"删除"在本设计里不是 DELETE
 *       （见 {@link AgentOwnershipRecord} 类注释与 §3.6.5 关于软删的论证）。
 *       于是这个分支在正常生命周期里应当<b>永远不出现</b>,
 *       而它一旦出现就是一个明确的信号: 有人对这张表做了硬删。
 *       返回 {@code Optional.empty()} 让调用方能安静地表示"她不存在",
 *       而不是让一次查询失败把整个 agent 的加载拖垮。</li>
 * </ol>
 * <p>把这两点写成"返回 Optional 而不是抛", 是因为相反的选择（抛
 * {@code NoSuchElementException} 之类）会让"我看到的是一个已经不存在的 agent"
 * 变成一个 500, 而它其实是一个应当被正常渲染成"该 agent 已不存在"的页面。
 *
 * <p><b>软删的行会被查出来, 这是刻意的。</b> {@code deleted_at != null} 的行
 * 仍然由 {@link #findByHumanId(String)} 返回, 调用方用
 * {@code AgentOwnershipRecord.alive()} 自己判断（"还活着吗"与"跑不跑"
 * 是两个问题, 见那个方法的说明）。这里<b>不</b>把过滤条件藏进方法名里
 * （例如提供一个 {@code findByHumanIdAndDeletedAtIsNull}）: 那样做会让
 * "她已经被删了"这条信息在读到它之前就消失, 而"控制台要显示已删除的 agent"
 * 与"恢复流程要跳过它"是两个不同的需求 —— 一个在查询里过滤掉软删行的接口,
 * 会让第一个需求只能靠另一个方法绕过去。
 *
 * <h2>为什么没有 {@code deleteByHumanId}</h2>
 * 因为它会是一个<b>语义错误的便利方法</b>。这张表的"删除"是
 * {@code lifecycle} 与 {@code deleted_at} 上的一次状态变更（§3.6.5 论证过
 * "停用"与"销毁"是两件事）, 而一个叫 {@code deleteBy...} 的方法
 * 会让下一个人的实现变成"删了归属行, 她的记忆/计划/关系网还在库里" ——
 * 那是一次<b>半截的销毁</b>, 而半截销毁比不销毁更难收拾（她没有主人了,
 * 也没有任何一行说她被删过）。真要硬删, 调用方应当显式用继承来的
 * {@code deleteById} 并自己承担"删干净了吗"这个问题。
 *
 * <h2>为什么有 {@code count} 而没有复杂统计</h2>
 * {@link #countByOwnerUserId(String)} 服务于配额/计费（§3.6.3:
 * "给 human 加字段与 provisioning、配额、计费没有任何关系"——
 * 反过来说, 配额要算的东西正好就在这张表上, 因为它才是平台侧的账）。
 * 更复杂的统计（"这个月创建了几个"）不该落在这里:
 * 它需要 {@code created_at} 的范围条件而本层没有索引覆盖它,
 * 而报表类查询属于读模型, 不属于这一层。
 */
public interface AgentOwnershipRecordRepository
        extends JpaRepository<AgentOwnershipRecord, String> {

    /**
     * 热路径: 这个 agent 归谁、现在跑不跑 —— 一次索引命中。
     *
     * <p>每次认知 tick 之前都会问一次（§3.6.6: {@code PAUSED} 停的是认知,
     * 所以判断点在 tick 的入口）, 因此它必须是单行查询。走
     * {@code uk_agent_ownership_human}。返回 {@code Optional} 的两种含义
     * 见类注释。
     */
    Optional<AgentOwnershipRecord> findByHumanId(String humanId);

    /** 这个 agent 有归属行吗 —— 与 {@link #findByHumanId} 同一条索引, 但不把整行读出来。 */
    boolean existsByHumanId(String humanId);

    /**
     * 这个用户名下全部 agent —— 控制台列表与配额计数, 走
     * {@code idx_agent_ownership_owner}。
     *
     * <p>按创建时间<b>正序</b>（最早创建的在前）: 与
     * {@code HumanRecordRepository.findAllByOrderByCreatedAtAsc} 同一条理由 ——
     * 一个稳定的、与内容无关的顺序, 而"最近创建的在前"会让整个列表在每次
     * 新建 agent 之后位移。
     */
    List<AgentOwnershipRecord> findByOwnerUserIdOrderByCreatedAtAsc(String ownerUserId);

    /** 这个用户名下有几个 agent —— 配额/计费。 */
    long countByOwnerUserId(String ownerUserId);

    /**
     * 某一档生命周期上的全部 agent —— {@code AgentLifecycle.wire()} 的字符串。
     *
     * <p>启动与恢复路径的真实用途: 进程起来之后要找出"哪些 agent 现在应当跑"
     * （{@code ACTIVE} 那一批）, 以及"哪些被暂停了、它们的未投递事件要不要
     * 按世界历史补齐"（§3.6.6 的 PAUSED 语义）。
     *
     * <p><b>刻意没有为这一列建索引</b>, 而这是一个可辩护的选择:
     * 这一列的基数在当前设计下只有两个取值, 而表里的行数等于 agent 数
     * （不是事件数 —— 那些表才是需要索引的地方）。一个两值索引在 PG 里
     * 几乎总是被规划器忽略, 建了只会让写入多一次维护。
     * <p>代价要说清楚: 这一列将来若变成"每个 agent 一个不同取值"
     * （例如有人把它当标签用）, 这个查询会变成全表扫描。届时该加索引 ——
     * 而判据是"基数变了", 不是"有人抱怨慢"。
     */
    List<AgentOwnershipRecord> findByLifecycleOrderByCreatedAtAsc(String lifecycle);

    /**
     * 现在<b>应当跑</b>的那些 agent —— {@code lifecycle} 是某一档 <b>且</b>没被软删。
     *
     * <p>这是 {@link #findByLifecycleOrderByCreatedAtAsc(String)} 的常用后继,
     * 而它之所以必须存在, 是因为"跑不跑"与"还活着吗"是<b>两个问题</b>
     * （§3.6.5）。启动时的真实用途: 进程起来之后要拉起"活着的、ACTIVE 的"
     * 那一批; 一个被软删但 {@code lifecycle} 还写着 {@code "active"} 的行
     * （软删它的那条路径没义务去改运行档 —— 那正是两列各答各的好处）
     * 不该被拉起来。
     *
     * <p>注意它<b>没有</b>合并成一个"状态"概念: 两个条件仍然以两个参数
     * 出现在方法名里, 调用方写的是 {@code findByLifecycleAndDeletedAtIsNull(lifecycle)}
     * —— 读代码的人一眼能看到这里问了两个问题。见
     * {@link AgentOwnershipRecord#paused()} 关于"不要包成 running()"的说明。
     *
     * <p>与 {@link #findByLifecycleOrderByCreatedAtAsc(String)} 一样, 这一列上
     * <b>没有索引</b>, 理由见该方法的说明（表行数 = agent 数, 基数低）。
     */
    List<AgentOwnershipRecord> findByLifecycleAndDeletedAtIsNullOrderByCreatedAtAsc(String lifecycle);

    /** 某一档上有几个 agent —— 平台健康面板（"现在有几个被暂停了"）。 */
    long countByLifecycle(String lifecycle);

    /**
     * 这个三方客户端建了哪些 agent —— 走了 {@code created_by_client_id} 的顺序扫描。
     *
     * <p>与 {@link #findByLifecycleOrderByCreatedAtAsc} 一样<b>没有索引</b>:
     * 它的用途是审计与一次性的对账（"这个客户端到底建了多少个"）,
     * 不在任何热路径上。这里也<b>不加</b> {@code @Index} —— 一个只被人工查询
     * 用到的列, 不该让每一次 provisioning 都多付一次索引维护。
     *
     * <p>注意它<b>查不到平台自建的那一批</b>（{@code created_by_client_id} 为
     * {@code NULL}, 而 {@code = ?} 不匹配 NULL）。要那一批得用
     * {@code findByCreatedByClientIdIsNull}, 而本接口刻意不提供它:
     * "平台自建的全部 agent"这个问题在语义上等于 {@code findAll()}
     * （今天的 provisioning 全是平台自建的）, 而提供一个看起来更精确的方法
     * 会让调用方以为它查到的是一个子集。
     */
    List<AgentOwnershipRecord> findByCreatedByClientIdOrderByCreatedAtAsc(String clientId);
}
