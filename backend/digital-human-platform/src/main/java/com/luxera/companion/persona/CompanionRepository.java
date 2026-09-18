package com.luxera.companion.persona;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface CompanionRepository extends JpaRepository<Companion, String> {
    List<Companion> findByUserIdAndDeletedAtIsNullOrderByCreatedAtAsc(String userId);
    long countByUserIdAndDeletedAtIsNull(String userId);

    /** 全部已删除的 Agent —— 只给 {@code GhostChatSweeper} 的一次性对账用。 */
    List<Companion> findByDeletedAtIsNotNull();

    /**
     * **该跑的** agent —— 活着的、且没被暂停的。所有遍历全部 agent 的定时任务用它。
     *
     * <h2>为什么把过滤放进查询, 而不是让每个任务自己 {@code continue}</h2>
     *
     * 因为那十几处循环**各自都写了 {@code if (c.getDeletedAt() != null) continue}**
     * —— 同一个判断抄了十几遍。再往里加"还要跳过暂停的"这一维, 就是把它抄第二遍,
     * 而这次每一处都是一个"漏了不会报错、只会继续烧钱"的机会。
     *
     * <p>收成一条查询之后, 新增一类任务时那行过滤是**不得不写对的**: 不调这个方法就拿
     * 不到 agent 列表, 而拿不到列表的任务根本跑不起来。"忘了也不会出事"的规则变成了
     * "忘了就编不过", 这是它比注释强的地方。
     *
     * <h2>为什么不是 {@code findAll()} 再在内存里筛</h2>
     *
     * 对 110 行来说两者开销都无所谓, 所以理由不是性能, 是**语义**: {@code findAll()}
     * 是一个不表达任何意图的名字, 而"哪些 agent 该跑"是一个有答案的问题。名字里没有
     * 答案的地方, 每个调用方就会自己编一个。
     *
     * <p>判据写成 {@code status is null or status <> 'paused'} 而不是
     * {@code status = 'active'}: 前者对将来可能出现的第三种值(比如 {@code 'sleeping'})
     * 的行为是"照常跑", 与 {@link AgentLifecycle#of(String)} 的宽松解析一致。
     * 两处判据必须是同一个意思 —— 一处宽松一处严格的话, 症状是"列表里有它、但它不动"。
     */
    @Query("select c from Companion c where c.deletedAt is null "
            + "and (c.status is null or lower(c.status) <> 'paused') "
            + "order by c.createdAt asc")
    List<Companion> findRunnable();

    /**
     * 还活着的 Agent —— 给聊天平台的**补铸**与**对账**用。
     *
     * <p>不带分页是刻意的: 它的调用方是两个一次性的 runner, 一次要的就是全量; 一个带
     * 分页的方法会诱使调用方写"翻页直到空"的循环, 而那个循环在补铸场景下正是我们最不想要的
     * 形状 —— 每翻一页数据就变一次(补铸会改 {@code chat_account_id}, 于是它自己把自己
     * 的下一页挤走)。
     *
     * <p>排序固定成 {@code createdAt}: 补铸是按顺序一件一件来的, 一个不定的顺序会让人
     * 无法从日志里判断"跑到哪了"。
     */
    List<Companion> findByDeletedAtIsNullOrderByCreatedAtAsc();

    /**
     * 还没有聊天账号的活 Agent —— 补铸 runner 的输入。
     *
     * <p>判据是 {@code chatAccountId is null} 而不是"查不到对应的 device": 后者要求本仓
     * 知道聊天平台的表, 那正是两个平台**不该**有的耦合。这一列是两边唯一约定的落点
     * (见 {@code Companion#chatAccountId} 的说明), 补铸是否完成以它为准。
     */
    List<Companion> findByDeletedAtIsNullAndChatAccountIdIsNullOrderByCreatedAtAsc();

    /**
     * 按**聊天账号**反查 agent —— 一键创建的幂等键。
     *
     * <p>唯一约束保证最多一行, 所以返回 {@code Optional} 而不是 {@code List}: 调用方要做的
     * 判断是"有没有", 而一个"可能有也可能没有的第二行"会诱使调用方写一个它并不需要的
     * 分支。带着 {@code deletedAt} 一起查回来(而不是过滤掉), 是为了让调用方能回答
     * "这个账号以前建过 agent, 后来被删了" —— 那两种情况要给的答复不一样。
     */
    Optional<Companion> findByChatAccountId(String chatAccountId);

    /**
     * 一个 API 客户端**看得见**的 agent 的判据 —— 三个分支对应三种形状, 缺一不可:
     *
     * <ol>
     *   <li>{@code created_by_client_id = :clientId} —— 本客户端建的, 含**代建**:
     *       代建出来的 agent 归真人所有, 但"是谁建的"那一栏写的是本客户端, 所以它看得见
     *       自己建了什么。</li>
     *   <li>{@code created_by_client_id IS NULL AND user_id = :clientId} ——
     *       {@code created_by_client_id} 这一列是后加的, 加列之前那些"客户端自己建的"
     *       agent 只有 {@code user_id = clientId} 这一个痕迹。少了这一支, 老客户端会突然
     *       看不到自己全部的 agent —— 而那种"数据还在、列表空了"的故障最难被当成 bug 报上来。</li>
     *   <li>其余一概看不见: 别的客户端的、以及真人自己建的, 都不在这个客户端的可见集里。</li>
     * </ol>
     *
     * <p>写成**一个常量**给下面两条查询共用, 而不是在两条 JPQL 里各抄一遍: 这个判据有
     * 三个分支和一段历史, 抄一遍不会立刻出错, 但会在此后每一次修改时只改一处 ——
     * 表现是"列表里有、点进去 404"(或反过来), 而那种不一致没有任何编译期信号。
     * (接口里的字段隐式是 {@code public static final}, 所以它能拼进注解值。)
     */
    String VISIBLE_TO_CLIENT = "c.deletedAt is null and ("
            + "c.createdByClientId = :clientId "
            + "or (c.createdByClientId is null and c.userId = :clientId))";

    /**
     * 一个 API 客户端看得见的 agent 列表 —— 8092 的 {@code GET /agents}。
     *
     * <p>注意它**只用于读**。写路径仍然走 {@code CompanionService#requireOwned},
     * 于是代建出来的 agent 看得见、改不动也删不掉 —— 那正是想要的边界: 代建不等于拥有。
     */
    @Query("select c from Companion c where " + VISIBLE_TO_CLIENT + " order by c.createdAt asc")
    List<Companion> findVisibleToClient(@Param("clientId") String clientId);

    /**
     * 同一个可见判据的单条版本 —— 8092 的 {@code GET /agents/{id}}。
     *
     * <p>单列一条查询而不是"取列表再在内存里 filter": 那个写法会为了看一个 agent 把该客户端
     * 全部 agent 连人格以外的东西一起读出来, 而它是个同步 HTTP 端点。
     */
    @Query("select c from Companion c where c.id = :agentId and " + VISIBLE_TO_CLIENT)
    Optional<Companion> findVisibleToClientById(@Param("clientId") String clientId,
                                                @Param("agentId") String agentId);
}
