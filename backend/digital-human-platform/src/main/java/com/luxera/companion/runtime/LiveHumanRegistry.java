package com.luxera.companion.runtime;

import com.luxera.companion.human.Human;
import com.luxera.companion.human.HumanContext;

import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * V2.2 §8.6.3 第 5/6 步的另一半 —— <b>活着的她在哪</b>。
 *
 * <h2>它填的是哪一个洞</h2>
 * {@link LiveHumanSource} 是 {@code AgentProfileProjector} 用来回答
 * "这个 {@code humanId} 在不在<b>这台机器</b>上"的那一个口子。在那个口子之前,
 * 唯一的实现是 {@link LiveHumanSource#NONE}, 而那是<b>当时的实话</b>:
 * 一个 {@code Human} 都没被装进来。
 *
 * <p>于是本类存在的意义不是"补一个空实现", 而是<b>把那个实话变成可变的</b>:
 * 座位表装配完成之后, 对同一个 {@code humanId} 的诚实回答就变了 ——
 * 她在这台机器上, 而且这里有一条能拿到她快照的路。
 *
 * <h2>为什么需要它, 而不让 {@code AgentRegistry} 直接持有 {@code Human}</h2>
 * 因为那会成环: {@code RecoveryRuntime} 要靠 {@code AgentRegistry} 才知道该装谁,
 * 而 {@code AgentRegistry} 要靠"装了谁"才知道 {@code materialized()} 报什么。
 * 一个可变的登记处把这个环拉直: 装配过程<b>往它里面写</b>, 档案投影<b>从它里面读</b>,
 * 两者的时序由装配层控制, 谁也不需要认识谁。
 *
 * <h2>为什么键是 {@code humanId} 而不是 {@code Human}</h2>
 * 因为读它的人拿到的是 id({@code AgentProfileProjector.project} 手上只有归属行与身份行),
 * 而不是对象。这也是 {@code WorldRuntime} 座位表的同一个键 —— 三张表
 * (座位表、世界绑定表、这一张)用同一个键, 于是"哪一边少了"这件事是可比的。
 *
 * <h2>快照取哪一个时刻</h2>
 * {@code Human.contextAt(clock.now())} 而不是 {@code Human.context()}。
 * 后者在"她还没有处理过任何事件"时<b>抛异常</b> —— 而那不是一种故障,
 * 那正是<b>每一个刚装配完的她的状态</b>: 座位登记完到第一拍心跳之间, 那个窗口是真的,
 * 而且控制台完全可能在那个窗口里来查一次。{@code context()} 抛的那个异常说得对
 * ("她还不知道时间"), 但它答不了"她现在怎么样"; {@code contextAt} 不发明时刻,
 * 它拿的是那个唯一的仿真时钟 —— 与 {@code WorldRuntime.lastInstant()} 同一个来源。
 *
 * <p>于是这里的选择是: <b>宁可给一份"她还没动过、时刻是此刻"的快照, 也不给
 * 一个 500</b>。快照面本来就是给眼睛看的(§3.1.4), 而一个查不出人的控制台
 * 会让"她刚被装进来"与"装配失败了"长得一模一样。
 *
 * <h2>线程安全</h2>
 * 写发生在装配线程(装配层), 读发生在控制台的 HTTP 线程 —— 与
 * {@code WorldRuntime} 的座位表同一种处境, 同样用 {@link ConcurrentHashMap}。
 * 这里不需要额外的锁: 每一个值都是<b>不可变的引用替换</b>, 而读到一个稍旧的集合
 * 与读到一个稍旧的快照是同一类可以接受的性质(见 {@code Human.contextAt} 的说明)。
 * {@code forget} 只被"她不再被装了"这一条路用到, 而那条路今天还不存在 ——
 * 留着它是为了让"软删之后档案该怎么变"有一个明确的落点, 而不是顺手从 Map 里删。
 */
public final class LiveHumanRegistry implements LiveHumanSource {

    private final SimulationClock clock;
    private final Map<String, Human> seated = new ConcurrentHashMap<>();

    public LiveHumanRegistry(SimulationClock clock) {
        this.clock = Objects.requireNonNull(clock, "登记处必须用那个唯一的仿真时钟 —— "
                + "快照的时刻不许来自第二处, 否则同一个人的两份快照会属于两条时间轴");
    }

    /**
     * 她装进来了。<b>由装配层在 {@code WorldRuntime.bind} 之后调用</b>。
     *
     * <p>顺序不是随意的: 先 bind 再登记的话, "在座位表上"与"在登记处里"
     * 之间没有窗口; 反过来, 控制台可能读到一个"她在这台机器上"而世界上还没有
     * 她那条线的瞬间。两个方向都不会崩, 但前者说的才是实话 ——
     * 前者意味着"她在这里, 而且世界已经能投递到她了"。
     *
     * @throws IllegalStateException 同一个 {@code humanId} 被登记了两次。
     *         这与 {@code WorldRuntime.bind} 拒绝重复座位是同一条理由:
     *         覆盖会让第一次那个引用静默丢失, 而它可能正是座位表里在跑的那一个
     */
    public void accept(String humanId, Human human) {
        Objects.requireNonNull(humanId, "登记必须知道登记的是谁");
        Objects.requireNonNull(human, "登记不能放进一个空的 Human");
        Human previous = seated.putIfAbsent(humanId, human);
        if (previous != null) {
            throw new IllegalStateException("humanId " + humanId + " 已经在登记处里了 —— "
                    + "覆盖会让座位表里正在跑的那一个变成没人能查到的孤儿: "
                    + "心跳照跑, 而控制台看到的是另一个对象");
        }
    }

    /**
     * 她不再在这台机器上了。
     *
     * <p>返回被移除的那一个(没有则 {@code null}), 让调用方能把"确实移除了一个"
     * 与"本来就没有"分开 —— 后者通常说明调用方手上的 {@code humanId} 记错了。
     */
    public Human forget(String humanId) {
        return seated.remove(humanId);
    }

    /**
     * 她的快照 —— 不在这台机器上时返回 {@code null}。
     *
     * <p>{@code null} 是 {@link LiveHumanSource} 的契约({@code NONE} 就是它),
     * 不是偷懒: {@code AgentProfileProjector} 要区分的是"她不在这台机器上"
     * 与"她在这里、状态是这些", 而一个 {@code Optional} 会逼着那个投影多做一次
     * 拆包却不增加任何信息。这条路径的<b>终点</b>是一个有名字的布尔
     * ({@code AgentProfileView.materialized()}), 不是一路传下去的空值。
     */
    @Override
    public HumanContext contextOf(String humanId) {
        Human human = seated.get(humanId);
        return human == null ? null : human.contextAt(clock.now());
    }

    /** 这台机器上装着谁 —— 给启动摘要与诊断面用。 */
    public Set<String> humanIds() {
        return Set.copyOf(seated.keySet());
    }

    /** 装了几个。 */
    public int size() {
        return seated.size();
    }

    public String describe() {
        return "登记处: " + seated.size() + " 个活着的她 " + seated.keySet();
    }

    @Override
    public String toString() {
        return describe();
    }
}
