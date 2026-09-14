package com.luxera.companion.runtime.application;

import com.luxera.companion.contracts.application.InvocationContext;
import com.luxera.companion.contracts.application.SessionRef;
import com.luxera.companion.contracts.spi.ApplicationRuntimePort;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.Optional;

/**
 * LAP v2 R13: <b>我该在哪一场里动手</b> —— 数字人回答这个问题的唯一一处。
 *
 * <h2>为什么需要它</h2>
 * <p>v1 里这个问题不存在: 一个 principal 在一个应用里至多一个会话, 于是"哪一场"是查出来的,
 * 甚至不必查 —— 它由 {@code installation} 唯一确定。v2 把它变成了一个真正的选择: 同一个人可以在
 * 同一个应用里开好几场, 还可能被人拉进别人开的场子。会话不再是"我的", 而是"我们正在用的"。
 *
 * <h2>四条定位策略, 按"我知道多少"从多到少</h2>
 * <ol>
 *   <li>{@link Strategy#EXPLICIT} —— 调用方直接说了是哪一场(事件 payload、路由结果)。</li>
 *   <li>{@link Strategy#RESOURCE} —— 没说, 但我要操作的那个资源行上记着它属于哪一场。
 *       这是最常见的一档: 资源与会话的关系是既成事实, 不需要问任何人。</li>
 *   <li>{@link Strategy#PARTICIPATING} —— 都没有, 但平台说"你此刻正在这场应用里的某一局中"。
 *       一次 {@code sessionsOf} 调用, 取最近活跃的那一场。</li>
 *   <li>{@link Strategy#DISCOVERABLE} —— 我不在任何一场里, 但有一场开着门({@code OPEN} 且没满)。
 *       走进去, 用 {@code joinSession} —— 与真人点"加入"是同一个动作。</li>
 * </ol>
 * <p>前两条是<b>只读的"我知道"</b>, 后两条要问平台。{@link #locate} 只走 1–3 且一次都不写,
 * 因为它跑在反应路径上: 那里每条事件都会来一次, 让"读一个资源"顺带产生一次加入或一次建会话,
 * 会把会话表灌满 —— 而这正是删掉 installation 之后最容易发生的退化。
 *
 * <h2>第四档只经 {@link #enter} 可达, 这是有意的</h2>
 * <p>"发现一场开着门的就进去"是一个<em>动作</em>, 不是一个<em>事实</em>。把它塞进只读的定位里,
 * 任何一次"读一下能不能动"都会变成"进去再说"。所以它被放在 {@link #enter} 里 —— 那个方法的名字
 * 就是承诺: 我准备好了要进去。{@link #Strategy#CREATED} 与 {@link Strategy#NONE} 不是定位策略,
 * 是两种结局(现场开一场 / 什么都没找到), 放在同一个枚举里是为了让调用方一次 switch 说完。
 *
 * <h2>{@code sessionsOf} 至多问一次</h2>
 * <p>策略 1、2 命中时不问平台。这不只是省一次调用: 那两条命中的时候, 答案与"我参与过哪些场子"
 * 无关 —— 拿一个已经确定的答案去换一次可能被截断(平台侧上限 50)的列表, 只会让一个本来正确的
 * 会话被换成一个碰巧更靠前的会话。
 */
@Slf4j
public class SessionResolver {

    private final ApplicationRuntimePort port;

    public SessionResolver(ApplicationRuntimePort port) {
        this.port = port;
    }

    /** 定位策略与两种结局。见类注释: 前四个是策略, 后两个是结局。 */
    public enum Strategy {
        /** 调用方直接点名。 */
        EXPLICIT,
        /** 资源行上记着。 */
        RESOURCE,
        /** 平台说我此刻就在里面。 */
        PARTICIPATING,
        /** 有一场开着门, 我走了进去。 */
        DISCOVERABLE,
        /** 一场都没有, 我开了一场。 */
        CREATED,
        /** 什么也没找到 —— 只读定位的失败, 不是一个可以返回的"会话"。 */
        NONE
    }

    /**
     * 一次定位所需的全部输入。四个字段都是"我已经知道的", 没有一个是"我要去查的"。
     *
     * @param companionId       谁在找 —— 只用于日志, 身份本身走 {@code ctx}
     * @param applicationId     哪个应用里找; 为 null 时策略 3、4 无法进行(见 {@link #locate})
     * @param explicitSessionId 调用方点名的那一场, 可为 null
     * @param resourceSessionId 资源行上那一场, 可为 null
     */
    public record Query(String companionId, String applicationId,
                        String explicitSessionId, String resourceSessionId) {

        /** 只知道"谁、在哪个应用里" —— 主动路径的起点(用户说了一句话, 还没碰任何资源)。 */
        public static Query of(String companionId, String applicationId) {
            return new Query(companionId, applicationId, null, null);
        }

        public Query withExplicit(String sessionId) {
            return new Query(companionId, applicationId, sessionId, resourceSessionId);
        }

        public Query withResource(String sessionId) {
            return new Query(companionId, applicationId, explicitSessionId, sessionId);
        }
    }

    /** 找到了: 哪一场, 以及凭什么找到的。 */
    public record Resolution(String sessionId, Strategy by) {}

    /**
     * 想把一个 {@link Query} 用在一次{@code enter} 之外的地方 —— 比如写进"我此刻在哪一场"的
     * 上下文里 —— 却进不去时抛这个。
     *
     * <p>它是 {@code RuntimeException} 而不是受检异常: 定位失败在反应路径上是常态(那条路根本不
     * 调 {@link #enter}), 而把常态做成受检异常, 只会让每个调用点写一个空的 catch。
     * {@code reason} 是一个稳定的短码, 供调用方分辨"该去兑票"与"这一场没了"。
     */
    public static class UnavailableException extends RuntimeException {
        private final String reason;

        public UnavailableException(String reason, String message) {
            super(message);
            this.reason = reason;
        }

        public String reason() {
            return reason;
        }
    }

    // ─────────────────────────── 只读定位 ───────────────────────────

    /**
     * 找一场<b>我已经有权动手</b>的会话 —— 只读, 一次都不写。
     *
     * <p>策略 1、2 是"我知道", 直接采信, <b>不去验证</b>。这不是偷懒: 验证意味着一次
     * {@code sessionsOf}, 而它可能截断; 更要紧的是, 一个资源行上写着的会话 id 与一个事件里
     * 点名的会话 id, 都是平台的既成事实, 不是调用方的猜测。真正的准入判定在
     * {@code ActionGateway} 里, 那里有参与者行、有 joinPolicy、有容量 —— 在这里再判一次,
     * 只是把同一条规则写成两份, 然后等着它们不一致。
     *
     * <p>{@code applicationId} 为 null 时只剩策略 1、2: 策略 3、4 都要问"这个应用里我能看见哪些
     * 会话", 而没有应用就没有这个问题。
     */
    public Optional<Resolution> locate(Query query, InvocationContext ctx) {
        if (query == null) {
            return Optional.empty();
        }
        if (hasText(query.explicitSessionId())) {
            return Optional.of(new Resolution(query.explicitSessionId(), Strategy.EXPLICIT));
        }
        if (hasText(query.resourceSessionId())) {
            return Optional.of(new Resolution(query.resourceSessionId(), Strategy.RESOURCE));
        }
        if (!hasText(query.applicationId())) {
            return Optional.empty();
        }
        List<SessionRef> visible = visibleSessions(query, ctx);
        SessionRef mine = firstJoined(visible);
        if (mine != null) {
            return Optional.of(new Resolution(mine.sessionId(), Strategy.PARTICIPATING));
        }
        return Optional.empty();
    }

    // ─────────────────────────── 确保在场 ───────────────────────────

    /**
     * <b>确保我在这场应用里有地方站</b> —— 能进现成的就进, 进不去就开一场。
     *
     * <p>与 {@link #locate} 的分别只有一个字: 这一个是承诺, 那一个是打听。所以它能写,
     * 也应该在有写权限的路径上被调用(接受邀请、数字人主动要玩点什么)。
     *
     * <p>顺序就是"越省事越靠前":
     * <ol>
     *   <li>定位到一场, 而我已经在里面 → 什么都不做。</li>
     *   <li>定位到一场, 它开着门({@code OPEN} 且没满) → {@code joinSession} 走进去。</li>
     *   <li>定位到一场, 但门是关着的({@code INVITE_ONLY})而我手里没票 → <b>抛</b>。
     *       这里绝不退回第 4 步: "我想进那一场, 进不去, 于是我另开一场"会把一次失败变成一次
     *       静默的复制 —— 邀请你的人在那场里等着, 而你在新的一场里对着空房间。要进那一场,
     *       唯一的办法是拿着票走 {@code joinByInvitation}。</li>
     *   <li>什么都没定位到, 但有一场开着门 → 走进去。这是"应用市场里有人开了公开局"。 </li>
     *   <li>什么都没有 → {@code ensureSession} 开一场, 我成为 OWNER。</li>
     * </ol>
     *
     * <p>第 3 步那个例外里有个细节值得写下来: {@code joinable()} 对"已经在场的人"永远为真,
     * 所以第 1 步其实被第 2 步覆盖了 —— 保留第 1 步只是为了让"我本来就在里面, 于是什么也没发生"
     * 这件事在代码里有一行, 而不是要靠读者去推。
     */
    public Resolution enter(Query query, InvocationContext ctx) {
        Optional<Resolution> located = locate(query, ctx);
        if (located.isPresent()) {
            String sessionId = located.get().sessionId();
            SessionRef ref = find(query, ctx, sessionId);
            if (ref == null) {
                // 定位给的是"我知道", 而我拿不出这一场 —— 只有一种情形: 它来自策略 1、2 的采信,
                // 平台那边根本看不见它。此时不能替调用方做主, 抛出去让他去兑票。
                throw new UnavailableException("SESSION_NOT_VISIBLE",
                        "会话 " + sessionId + " 不在我能看见的范围内, 无法进入");
            }
            if (ref.joined()) {
                return new Resolution(sessionId, located.get().by());
            }
            if (ref.joinable()) {
                port.joinSession(sessionId, ctx);
                log.info("[SessionResolver] 走进开着门的会话: companion={}, session={}",
                        query.companionId(), sessionId);
                return new Resolution(sessionId, located.get().by());
            }
            throw new UnavailableException("NEEDS_INVITATION",
                    "会话 " + sessionId + " 需要邀请(invite-only 或已满), 请凭票加入");
        }

        if (hasText(query.applicationId())) {
            for (SessionRef ref : visibleSessions(query, ctx)) {
                if (ref.joinable()) {
                    if (!ref.joined()) {
                        port.joinSession(ref.sessionId(), ctx);
                    }
                    log.info("[SessionResolver] 走进去: companion={}, session={}, alreadyJoined={}",
                            query.companionId(), ref.sessionId(), ref.joined());
                    return new Resolution(ref.sessionId(), Strategy.DISCOVERABLE);
                }
            }
        }

        String created = port.ensureSession(query.applicationId(), ctx);
        log.info("[SessionResolver] 一场都没有, 开了一场: companion={}, application={}, session={}",
                query.companionId(), query.applicationId(), created);
        return new Resolution(created, Strategy.CREATED);
    }

    // ─────────────────────────── 内部 ───────────────────────────

    /**
     * 这一场在我看得见的列表里长什么样。
     *
     * <p>策略 1、2 命中时, 被点名的会话<em>未必</em>在 {@code sessionsOf} 里 —— 我只在里面的时候
     * 它才在。于是这里要问平台, 而这是本类第二次、也是最后一次问它。问不到就返回 null,
     * 由调用方决定这是"抛"还是"算了"。
     */
    private SessionRef find(Query query, InvocationContext ctx, String sessionId) {
        if (!hasText(query.applicationId())) {
            return null;
        }
        for (SessionRef ref : visibleSessions(query, ctx)) {
            if (sessionId.equals(ref.sessionId())) {
                return ref;
            }
        }
        return null;
    }

    /**
     * {@code sessionsOf} 的包装: 它失败不该让整条链炸掉。
     *
     * <p>看到的是一个空列表, 与"我没有任何会话"在下游是同一件事 —— 于是失败的表现是"另开一场",
     * 而不是一次异常。这是刻意的: 会话服务短暂的不可用不该让数字人的一次事件处理整个失败,
     * 而另开一场的代价是可控的(下一条事件会重新定位)。异常仍然记 WARN —— 把它悄悄吞掉会让
     * "为什么数字人手上会话越开越多"变成一个查不出来的问题。
     */
    private List<SessionRef> visibleSessions(Query query, InvocationContext ctx) {
        try {
            List<SessionRef> sessions = port.sessionsOf(query.applicationId(), ctx);
            return sessions == null ? List.of() : sessions;
        } catch (Exception e) {
            log.warn("[SessionResolver] 读可见会话失败 application={}: {}",
                    query.applicationId(), e.getMessage());
            return List.of();
        }
    }

    /** 已经在前面的那些排在最前(平台保证尾部截断), 所以"第一个我参与过的"就是最近的那一场。 */
    private static SessionRef firstJoined(List<SessionRef> sessions) {
        for (SessionRef ref : sessions) {
            if (ref.joined() && ref.live()) {
                return ref;
            }
        }
        return null;
    }

    private static boolean hasText(String s) {
        return s != null && !s.isBlank();
    }
}
