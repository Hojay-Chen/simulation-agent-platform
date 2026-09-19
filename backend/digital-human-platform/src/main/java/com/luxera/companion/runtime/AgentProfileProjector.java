package com.luxera.companion.runtime;

import com.luxera.companion.human.HumanContext;
import com.luxera.companion.persistence.entity.AgentOwnershipRecord;
import com.luxera.companion.persistence.entity.ConversationAccountBindingRecord;
import com.luxera.companion.persistence.entity.HumanRecord;
import com.luxera.companion.persistence.repository.ConversationAccountBindingRecordRepository;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * V2.2 §3.6.7 / §8.5.7 —— <b>把三份输入现场拼成一份 {@link AgentProfileView}。不落库。</b>
 *
 * <pre>
 *   输入:  AgentOwnershipRecord      （她归谁、跑不跑、删没删 —— {@code agent_ownership}）
 *          HumanRecord              （她的仿真身份行 —— {@code human}）
 *          绑定行                    （她在聊天平台上的账号 —— {@code conversation_account_binding}）
 *          LiveHumanSource          （她此刻在不在这台机器上, 以及她的只读快照）
 *   输出:  AgentProfileView          （一份不可变快照）
 * </pre>
 *
 * <h2>为什么"不落库"是本类存在的全部理由</h2>
 *
 * 见 {@link AgentProfileView} 的类注释。这里只补一句<b>代价</b>, 因为一个只讲好处的
 * 说明是不完整的: 每次读要现场投影, 比读一张宽表慢。§3.6.7 明确说这个代价是明知的、
 * 可接受的 —— 控制台读的是<b>一个人的当前状态</b>, 不是一万行报表。
 *
 * <p>而反过来的代价是不可接受的: 一张物化的档案表需要有人在每次她变化时更新它,
 * 而那个"有人"迟早会漏掉某个变化路径。漏掉的表现就是那句"界面上显示她心情不错,
 * 而她的情绪状态里其实是疲惫"。
 *
 * <h2>三份输入缺一不可, 而缺了的表现各不相同</h2>
 * <table border="1">
 *   <tr><th>缺哪一份</th><th>表现</th></tr>
 *   <tr><td>归属行</td><td>她存在, 但"归谁、跑不跑"答不了 —— 本类<b>不投影</b>
 *       （返回 {@code Optional.empty()} 的是调用方 {@code AgentRegistry}）</td></tr>
 *   <tr><td>human 行</td><td>归属行悬空（§7.2: 这张表没有外键, 悬空是可能的）。
 *       此时<b>照样投影</b>, 只是名字为空 —— "这个 agent 归属还在、身份行没了"
 *       是一个必须能显示出来的状态, 而不是一个该被隐藏的异常</td></tr>
 *   <tr><td>绑定行</td><td>空列表。这不是错误: provisioning 与绑定是两步,
 *       "她还没有任何聊天账号"是正常状态</td></tr>
 * </table>
 *
 * <h2>本类没有时钟, 这是一个约束而不是巧合</h2>
 *
 * 它<b>一次都不读</b> {@code Instant.now()}。所有时刻都来自输入: 归属行上的
 * {@code createdAt}/{@code updatedAt}（墙上时钟, 平台概念）与绑定行上的
 * {@code boundAt}（仿真时刻, 参与回放）。快照里那个 {@code HumanContext} 带的时刻
 * 由 {@link LiveHumanSource} 的实现决定 —— 而那是装配层, 它握着 {@code SimulationClock}。
 *
 * <p>理由与全仓一致: 一个自己读"现在"的读模型, 会让"这一页显示的是哪一刻的她"
 * 变成一个无法回答的问题。§3.1.4 给 {@code HumanContext} 的注释里记着这条真实 bug
 * （{@code EventFabric.describe} 曾经"凑一个现在"）。
 */
public final class AgentProfileProjector {

    private final ConversationAccountBindingRecordRepository bindings;
    private final LiveHumanSource live;

    /**
     * @param bindings 她的聊天账号绑定。按 {@code humanId} 查, 按 {@code boundAt} 升序 ——
     *                 顺序稳定这一点很重要: 一份每次刷新都换顺序的列表, 在界面上看起来
     *                 就是"她的账号在跳"
     * @param live     她此刻在不在这台机器上。今天传
     *                 {@link LiveHumanSource#NONE}（第 5/6 步还没落地）
     *                 —— <b>不许传 {@code null}</b>: "她不在这台机器上"已经有一个
     *                 有名字的答案了, 而 {@code null} 在这里会变成"投影器忘了配"与
     *                 "她确实不在"两件事的同一个写法
     */
    public AgentProfileProjector(ConversationAccountBindingRecordRepository bindings,
                                LiveHumanSource live) {
        this.bindings = Objects.requireNonNull(bindings,
                "绑定表是必给的 —— 没有它就投影不出她的聊天账号, "
                        + "而'她没有账号'与'查询没配'会变成同一个空列表");
        this.live = Objects.requireNonNull(live,
                "LiveHumanSource 是必给的 —— 今天的事实用 LiveHumanSource.NONE 表达, "
                        + "而不是用 null");
    }

    /**
     * 投影一份档案。
     *
     * @param ownership 归属行 —— <b>不可为 {@code null}</b>。没有它就没有档案,
     *                  那是调用方要处理的分支（{@code AgentRegistry} 返回
     *                  {@code Optional.empty()}）, 而不是这里要渲染的空壳
     * @param human     {@code human} 行; {@code null} = 悬空归属行, 见类注释的表格
     */
    public AgentProfileView project(AgentOwnershipRecord ownership, HumanRecord human) {
        String humanId = ownership.getHumanId();
        AgentLifecycle parsed = AgentLifecycle.of(ownership.getLifecycle());

        List<AgentProfileView.AccountRef> accounts = new ArrayList<>();
        for (ConversationAccountBindingRecord b : bindings.findByHumanIdOrderByBoundAtAsc(humanId)) {
            accounts.add(new AgentProfileView.AccountRef(
                    b.getChatAccountId(),
                    b.getPersonId(),
                    b.getBindReason(),
                    b.getBoundAt(),
                    b.bootstrap()));
        }

        HumanContext context = live.contextOf(humanId);

        return new AgentProfileView(
                humanId,
                human == null ? null : human.getDisplayName(),
                ownership.getOwnerUserId(),
                ownership.getLifecycle(),
                parsed.wire(),
                parsed.isPaused(),
                ownership.alive(),
                ownership.alive() && !parsed.isPaused(),
                ownership.getCreatedByClientId(),
                ownership.platformCreated(),
                ownership.getCreatedAt(),
                ownership.getUpdatedAt(),
                ownership.getDeletedAt(),
                List.copyOf(accounts),
                context);
    }
}
