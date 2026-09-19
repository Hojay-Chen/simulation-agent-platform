package com.luxera.companion.runtime;

import com.luxera.companion.persistence.entity.AgentOwnershipRecord;
import com.luxera.companion.persistence.entity.HumanRecord;
import com.luxera.companion.persistence.repository.AgentOwnershipRecordRepository;
import com.luxera.companion.persistence.repository.HumanRecordRepository;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * V2.2 §3.6.8 / §8.5.7 —— <b>花名册: 平台上有哪些 agent、它们归谁、现在跑不跑。</b>
 *
 * <pre>
 *   取代:  persona.CompanionService          （旧的"伴侣"服务, 从 companions 表拼 Persona）
 *   拥有:  agent_ownership                   （归属、运行档、软删）
 *   读者:  平台 —— 控制台、运维面、/api/**     <b>不是</b>她（心跳不从本类读任何东西）
 *   写者:  运维面（暂停/恢复）—— 见下面"本类不做什么"
 * </pre>
 *
 * <h2>它为什么叫这个名字 —— 一场刻意接受的撞名</h2>
 *
 * 本包里原来已经有一个 {@code AgentRegistry}（V11 §52/§53, "按名称注册认知 Agent",
 * 841 字节）。§8.5.7 把这次撞名记了下来, 而结论是<b>本类占这个名字</b>:
 *
 * <blockquote>
 * "平台上有哪些 agent、它们归谁、现在跑不跑"本来就该是这个名字。
 * </blockquote>
 *
 * <p>那个旧类确实是另一个东西 —— 它装的是认知链上的<b>处理器</b>
 * （情感/大脑/记忆/表达/事件模拟这五个零件), 而本类装的是<b>数字人</b>。
 * 后者才是产品语义上的 Agent, 前者是它的零件。所以旧的那个改名叫
 * {@link CognitiveAgentRegistry} —— 一个描述它装着什么的、诚实的名字,
 * 而不是一个为让路而起的 {@code LegacyAgentRegistry}。
 *
 * <h2>它<b>不</b>物化 {@code Human} —— 这一步在装配层</h2>
 *
 * 本类回答"库里有哪些 agent"; 把其中某几个变成内存里活着的 {@code Human} 聚合、
 * 装进 {@code WorldRuntime} 的座位表, 是 {@code SimulationConfiguration} 的第 5/6 步。
 * 两者之间那条缝是 {@link LiveHumanSource}: 本类把 {@link #profile} 的一问交给它,
 * 而自己不认识 {@code WorldRuntime}, 也不认识任何一张座位表。
 *
 * <p>分开的理由是 §8.5.7 那句"心跳只从它读一个 {@code AgentLifecycle}":
 * 花名册是<b>平台</b>的账, 心跳是<b>世界</b>的事。让花名册握着运行时,
 * 就等于让一次控制台查询与一次心跳共享一个对象 —— 于是"她为什么不动了"
 * 又开始有两个可能的答案。
 *
 * <h2>本类不做什么</h2>
 * <ul>
 *   <li><b>不创建 agent。</b> {@code human.id} 与 {@code companion.id} 是<b>同一个值</b>
 *       （见 {@code HumanRecord} 的类注释: "换一套 id 会让全部既有数据的外部引用失效"）,
 *       所以这里没有 {@code generate()} 也没有 {@code provision()} —— 一个"随手造一个她"
 *       的入口, 会造出一个没有 companion、没有人设、没有记忆的 id。
 *       新 agent 从平台既有的创建流程来;</li>
 *   <li><b>不删任何东西。</b> §3.6.5: 软删有自己的写入路径与清理逻辑
 *       （"软删一个 agent 意味着她的身体、计划、关系网怎么办"是那段逻辑要回答的问题）。
 *       本类只<b>读</b> {@code deletedAt} 并把它如实投影出来
 *       （{@link AgentProfileView#alive()}）;</li>
 *   <li><b>不读时钟。</b> 所有时刻要么来自库里的列, 要么来自 {@link LiveHumanSource}
 *       的实现 —— 那是装配层, 它握着 {@code SimulationClock}。</li>
 * </ul>
 *
 * <h2>"哪些 agent 现在应当跑"为什么<b>不能</b>是一条 SQL</h2>
 *
 * 这是本类里最要紧的一个决定, 而它有一个看起来更自然、实际是错的写法:
 *
 * <pre>
 *   ❌ repository.findByLifecycleAndDeletedAtIsNullOrderByCreatedAtAsc("active")
 *
 *      这一列的取值不是由我们独占的, 而 §3.6.5 的兜底规则是:
 *      <b>只有字面写着 "paused" 才停, 别的一律当运行</b>（认不出来时往"继续跑"的方向兜,
 *      因为"误判成停"会让这个 agent 变成哑巴, 而那是没有报错的故障）。
 *
 *      于是库里有这么一行时:
 *        lifecycle = "sleeping"      （有人写了个我们没定义的值）
 *      上面那句 SQL <b>查不到它</b> —— 也就是把它判成了"不要跑"。
 *      而 AgentLifecycle.of("sleeping") = ACTIVE, 也就是说运行时<b>本该让她跑</b>。
 *      两个答案相反, 而两边看起来都对: SQL 那边是一句无懈可击的条件查询,
 *      枚举那边是一段有注释辩护的兜底。症状是"她被静默地停了, 而库里没有任何
 *      一列写着 paused"。
 * </pre>
 *
 * <p>所以 {@link #runnable()} 的做法是: <b>把行读回来, 用枚举判</b>。
 * 代价是全表扫描 —— 而 §7.2 与那个 repository 的注释都写着这张表的行数
 * <b>等于 agent 数</b>（不是事件数）, 所以这个代价在今天是一个几十行的循环。
 * 判据是"口径只有一个"而不是"少扫几行"。
 *
 * <p>注意 {@code AgentOwnershipRecordRepository} 上<b>确实</b>提供了那两个按
 * {@code lifecycle} 查的方法, 而且它们对别的用途是对的（例如运维要问
 * "库里写着 paused 的有几个"—— 那问的就是字面值）。错的是拿它们回答
 * "谁应当跑"这个语义问题。
 */
public final class AgentRegistry {

    private final AgentOwnershipRecordRepository ownership;
    private final HumanRecordRepository humans;
    private final AgentProfileProjector projector;

    public AgentRegistry(AgentOwnershipRecordRepository ownership,
                         HumanRecordRepository humans,
                         AgentProfileProjector projector) {
        this.ownership = ownership;
        this.humans = humans;
        this.projector = projector;
    }

    // ───────────────────────────── 读 ─────────────────────────────

    /**
     * 全部在册的 agent, 按归属行创建时刻正序。
     *
     * <p>正序而不是倒序, 与 {@code HumanRecordRepository} 的方法同一条理由:
     * 一个稳定的、与内容无关的顺序。倒序会让整个列表在每次新建 agent 之后<b>位移</b>,
     * 而控制台的操作员正在看着它。
     *
     * <p>包含了被软删的那些 —— {@code AgentOwnershipRecordRepository} 的类注释里
     * 论证过为什么软删的行也应当被读出来("控制台要显示已删除的 agent"与
     * "恢复流程要跳过它"是两个需求)。要过滤的调用方用
     * {@link AgentProfileView#alive()} 自己判 —— 那是它该写的那个合取。
     */
    public List<AgentProfileView> roster() {
        return project(ownership.findAll());
    }

    /** 某个平台用户名下的 agent —— 控制台列表与配额计数。 */
    public List<AgentProfileView> rosterOf(String ownerUserId) {
        return project(ownership.findByOwnerUserIdOrderByCreatedAtAsc(ownerUserId));
    }

    /**
     * 一个人 —— {@code humanId} 查不到归属行时返回 {@code Optional.empty()}。
     *
     * <p>返回 {@code Optional} 而不是抛, 是 {@code AgentOwnershipRecordRepository}
     * 类注释里那条结论的延续: "这不是一个 agent"（id 打错了、或者是别的表的主键）
     * <b>是一个答案</b>, 而运维排查时的第一个动作就是查它 —— 那个 404 该被渲染成
     * "该 agent 已不存在", 而不是一个 500。
     */
    public Optional<AgentProfileView> profile(String humanId) {
        return ownership.findByHumanId(humanId)
                .map(row -> projector.project(row, humans.findById(humanId).orElse(null)));
    }

    /** 这个 humanId 在册吗 —— 与 {@link #profile} 同一条索引, 但不把整行读出来。 */
    public boolean registered(String humanId) {
        return ownership.existsByHumanId(humanId);
    }

    /**
     * <b>现在应当跑</b>的那一批 —— 装配层的第 5/6 步要的就是它。
     *
     * <p>"应当跑"是 {@code alive && !paused}。注意这个合取在方法体里是<b>分开写的</b>
     * 两个判断（而不是一个 {@code running} 谓词的二次封装）—— 见
     * {@code AgentOwnershipRecord} 关于"不要把它包成 running()"的说明:
     * 合起来之后, "她被删了"与"她被暂停了"在调用处再也分不开。
     *
     * <p>口径见类注释"为什么不能是一条 SQL"。
     */
    public List<AgentProfileView> runnable() {
        List<AgentProfileView> out = new ArrayList<>();
        for (AgentProfileView v : roster()) {
            if (v.alive() && !v.paused()) {
                out.add(v);
            }
        }
        return List.copyOf(out);
    }

    /**
     * 她的运行档 —— 心跳在每次 tick 之前要问的那一个 (§3.6.6: {@code PAUSED} 停的是认知,
     * 所以判断点在 tick 的入口)。
     *
     * <p>查不到归属行时返回 {@link AgentLifecycle#ACTIVE}: 与
     * {@code AgentLifecycle.of} 同一条兜底方向 —— 一个没有归属行的 id 意味着
     * "这一行还没被 provisioning 出来", 而那不是"她该停"。
     */
    public AgentLifecycle lifecycleOf(String humanId) {
        return ownership.findByHumanId(humanId)
                .map(row -> AgentLifecycle.of(row.getLifecycle()))
                .orElse(AgentLifecycle.ACTIVE);
    }

    /** 全部在册的 agent 数（含软删的）。 */
    public long total() {
        return ownership.count();
    }

    /**
     * 平台健康面板要的那几个数 —— <b>按库里的字面值分组</b>, 而不是按枚举。
     *
     * <p>与 {@link #runnable()} 的口径刻意不同, 而且这个差别是有意的:
     * <pre>
     *   runnable() 回答"谁在跑"  → 用枚举判（认不出来的算跑）
     *   counts()   回答"库里各档分别有几行" → 用字面值分组（那一列写了什么就报什么）
     * </pre>
     * 于是"写着 paused 的有 3 个、而 runnable() 是 5 个"这句话是可以同时成立的,
     * 而它正是运维要的那个信号: 有 2 个 agent 的运行档<b>认不出来</b>, 它们会一直跑。
     * 把两者对齐成同一个口径, 这个信号就消失了 —— 而那恰好是
     * {@code AgentProfileView#lifecycleDrifted()} 要显示出来的东西。
     *
     * @return 字面值 → 行数。顺序按字面值排（稳定的输出）
     */
    public Map<String, Long> countsByLiteralLifecycle() {
        Map<String, Long> out = new LinkedHashMap<>();
        ownership.findAll().stream()
                .collect(Collectors.groupingBy(
                        row -> row.getLifecycle() == null ? "" : row.getLifecycle(),
                        Collectors.counting()))
                .entrySet().stream()
                .sorted(Comparator.comparing(Map.Entry::getKey))
                .forEach(e -> out.put(e.getKey(), e.getValue()));
        return out;
    }

    // ───────────────────────────── 写 ─────────────────────────────

    /**
     * 把她的运行档设成 {@code target} —— 控制台上那个"停 / 继续"。
     *
     * <p><b>它只写库里那一列。</b> 让正在跑的那一份也跟着改, 是<b>调用方的事</b>:
     * 装配层同时握着本类与 {@code WorldRuntime}, 它在本方法返回后调
     * {@code WorldRuntime.applyLifecycle(humanId, target)}。本类不握 {@code WorldRuntime}
     * —— 那会让一次控制台写操作与一条心跳线程共享一个对象, 而类注释里那段
     * "花名册是平台的账"说的就是这个。
     *
     * <p>注意这里<b>不做</b>"认不出来的值兜底成 ACTIVE"那件事: 兜底是<b>读</b>侧的规则
     * （§3.6.5）, 写侧写的就是调用方给的那一档, 它是枚举成员, 不可能是别的字。
     *
     * @return 这一列真的变了吗。{@code false} = 它本来就是这一档（重复按同一个按钮,
     *         而"重复提交幂等"正是 {@code PUT .../lifecycle} 这个形状买到的东西之一）
     */
    public boolean setLifecycle(String humanId, AgentLifecycle target) {
        AgentOwnershipRecord row = ownership.findByHumanId(humanId).orElse(null);
        if (row == null) {
            return false;
        }
        if (target.wire().equalsIgnoreCase(row.getLifecycle() == null ? "" : row.getLifecycle().trim())) {
            return false;
        }
        row.setLifecycle(target.wire());
        ownership.save(row);
        return true;
    }

    // ───────────────────────────── 内部 ─────────────────────────────

    /**
     * 把一批归属行投影成视图 —— <b>human 行只查一次</b>。
     *
     * <p>一次 {@code findAll()} 拿全部 human 行做一个 map, 而不是每行查一次:
     * 后者是 N+1, 而它在控制台上会表现为"agent 列表随人数变慢", 一个会被归因到
     * "数据库该加索引了"的症状。这里的取舍与别处一致 —— 表的行数等于 agent 数,
     * 所以"全都读回来"是一个几十行的操作。
     *
     * <p>排序在这里做而不是靠 repository 的方法名: {@link #roster()} 走的是
     * {@code findAll()}（它没有排序保证）, 而"列表顺序稳定"这件事只能有一个地方负责。
     */
    private List<AgentProfileView> project(List<AgentOwnershipRecord> rows) {
        if (rows.isEmpty()) {
            return List.of();
        }
        Map<String, HumanRecord> byId = new LinkedHashMap<>();
        for (HumanRecord h : humans.findAllByOrderByCreatedAtAsc()) {
            byId.put(h.getId(), h);
        }

        List<AgentOwnershipRecord> sorted = new ArrayList<>(rows);
        sorted.sort(Comparator.comparing(AgentOwnershipRecord::getCreatedAt,
                Comparator.nullsLast(Comparator.naturalOrder())));

        List<AgentProfileView> out = new ArrayList<>(sorted.size());
        for (AgentOwnershipRecord row : sorted) {
            out.add(projector.project(row, byId.get(row.getHumanId())));
        }
        return List.copyOf(out);
    }
}
