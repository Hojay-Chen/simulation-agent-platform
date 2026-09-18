/**
 * V2.2 §7 —— <b>持久化层</b>: 把 {@code human/}、{@code world/}、{@code boundary/} 里的活对象
 * 摊平成关系型行, 再原样读回来。
 *
 * <h2>它为什么必须是一个新包, 而不是塞进 human/ 或 world/</h2>
 * 这是那道"Human ⟂ World 零交互"(§9 验收标准 F, 由 {@code V22BoundaryArchitectureTest} 钉死)
 * 的直接推论, 而不是审美选择:
 * <table border="1">
 *   <tr><th>放哪</th><th>会出什么事</th></tr>
 *   <tr>
 *     <td>放 {@code human/}</td>
 *     <td>它要持久化 {@code boundary.event.WorldEvent} —— 于是 {@code human/} 里出现了一条
 *         "通往事件"的路。今天它只是把一个 {@code WorldEvent} 写进表; 明天就会有人
 *         "顺手"在这里 {@code instanceof} 一个具体事件来补一个字段。<b>而
 *         {@code theHumanNeverReachesIntoTheWorld} 抓不住它</b> —— 因为事件住在
 *         {@code boundary/} 里, 而那条规则只拦 {@code world/}</td>
 *   </tr>
 *   <tr>
 *     <td>放 {@code world/}</td>
 *     <td>对称的一半: 它要持久化 {@code human.life.activity.Activity}。于是世界侧
 *         认识了"她在做什么" —— 而"世界不该知道她是谁、她在做什么"正是
 *         {@code theWorldNeverReachesIntoTheHuman} 要保住的东西</td>
 *   </tr>
 *   <tr>
 *     <td>放 {@code boundary/}</td>
 *     <td>最坏的一个。{@code theBoundaryItselfKnowsNeitherSide} 明说边界
 *         "一旦认识任何一边, 前两条规则就可以被绕过" —— 而一个同时 import 两边的
 *         持久化层, 会把 {@code world → boundary → human} 这条握手链路坐实</td>
 *   </tr>
 *   <tr>
 *     <td><b>新包 {@code persistence/}</b></td>
 *     <td>三条规则都不扫它, 而这是对的: 它不是任何一侧的一部分, 它是
 *         <b>装配层</b> —— 就像 Spring 的 {@code @Configuration} 同时认识两边一样。
 *         三方规则扫不到它, 代价由下面这条纪律补上: <b>本包只做映射, 不做决策</b></td>
 *   </tr>
 * </table>
 *
 * <h2>本包的纪律: 只做映射, 不做决策</h2>
 * 一个"没人扫得到我"的包很容易长成第二个业务层。所以这里把话说死:
 * <ul>
 *   <li><b>不读时钟 —— 指的是不读<i>仿真</i>时钟。</b> 每一个<b>仿真时刻</b>都由调用方
 *       传进来（{@code Instant} 那些列: {@code occurred_at} / {@code started_at} /
 *       {@code booked_at}）。理由与 {@code ContinuousEffectLedger.book(event, bookedAt)}
 *       完全相同 —— 仿真时钟跑在加速或减速的时间轴上, 而一个自己读墙上时钟的持久化层,
 *       会让"把仿真加速 60 倍"变成"数据库里的时间戳乱了"。<br>
 *       这一条<b>不</b>禁止 {@code @CreationTimestamp} / {@code @UpdateTimestamp}
 *       那两个<b>审计列</b>（{@code created_at} / {@code updated_at}）: 它们是运维在看的
 *       墙上时间, <b>本来就该</b>随真实时间走 —— "这一行是 2026-09-19 写进来的"
 *       不该随着仿真的倍率变化。两者的分界线是"这个时刻参不参与回放":
 *       {@code Instant} 参与, {@code LocalDateTime} 的审计列不参与。
 *       反过来说, 一个平台侧的动作（软删一个 agent）用 {@code LocalDateTime}
 *       而不是 {@code Instant} 是有意的 —— 见 {@code AgentOwnershipRecord}
 *       "这张表上一个仿真时刻列都没有";</li>
 *   <li><b>不做领域判断。</b> 不解释状态、不改写字段、不"顺手"修一个看起来不对的值。
 *       读回来的东西与写下去的不一样, 是这一层唯一不可接受的故障 ——
 *       它会让"她上周三为什么改主意"这个问题的答案<b>取决于你什么时候读的</b>;</li>
 *   <li><b>不新增领域类型。</b> 本包只认识 {@code human/life}、{@code world/}、
 *       {@code boundary/} 里已有的类型。要落一张新表, 前提是领域里已经有了那个对象。</li>
 * </ul>
 *
 * <h2>三块内容, 各管一件事</h2>
 * <table border="1">
 *   <tr><th>子包</th><th>是什么</th><th>谁在用</th></tr>
 *   <tr>
 *     <td>{@code entity}</td>
 *     <td>JPA 实体 —— 表的样子。<b>只有字段</b>, 没有一个判断</td>
 *     <td>Hibernate</td>
 *   </tr>
 *   <tr>
 *     <td>{@code repository}</td>
 *     <td>Spring Data 接口 —— 查询的样子。<b>方法名就是查询</b>, 没有实现</td>
 *     <td>store 子包</td>
 *   </tr>
 *   <tr>
 *     <td>{@code store}</td>
 *     <td>领域对象 ⇄ 实体 的转换 —— 唯一允许同时认识两边的地方</td>
 *     <td>runtime / 恢复流程 / 测试</td>
 *   </tr>
 * </table>
 *
 * <h2>建表方式: 沿用本仓既有的 {@code ddl-auto: update}, 不引入 Flyway</h2>
 * 结论来自命令 {@code grep -rn flyway --include=*.gradle --include=*.yml .} ——
 * <b>零命中</b>。本仓从 V9 起的所有表都由 Hibernate 的
 * {@code spring.jpa.hibernate.ddl-auto: update}
 * (见 {@code server/src/main/resources/application.yml} 与
 * {@code openapi/src/main/resources/application.yml}) 建出来, 仓库里
 * {@code find . -name "*.sql"} 一个文件都没有。
 *
 * <p>因此本包<b>不加</b> {@code @Table(..., schema=...)} 之外的东西, 也不写建表脚本 ——
 * 引入 Flyway 会让"表结构"出现两个真相源(注解 + 迁移脚本), 而它们迟早不一致;
 * 而不一致的那一天, 现象是"本地能跑、线上少一列"。
 *
 * <p>代价写在明处: {@code ddl-auto: update} <b>只会加表和加列, 永远不会删列、改类型、
 * 加 NOT NULL</b>。所以本包里的列刻意都允许为空(宁可让读的人处理 null, 也不指望
 * Hibernate 去改一个已有列), 而"什么时候该上真正的迁移工具"是一个留给部署侧的判断,
 * 不是本层能替它做的。
 *
 * <h2>多态怎么落库 —— 一句话</h2>
 * <b>稳定的关系型元数据(类型三元组) + 一列 JSON 载荷</b>。类型名而不是类名落在列上,
 * 载荷里同时冗余一份 {@code _type}。为什么冗余、为什么是 JSON 而不是每种事件一张表,
 * 见 {@link com.luxera.companion.persistence.DomainPayloadCodec} 的类注释 ——
 * 那一篇是整个 §7.3 的论证所在。
 */
package com.luxera.companion.persistence;
