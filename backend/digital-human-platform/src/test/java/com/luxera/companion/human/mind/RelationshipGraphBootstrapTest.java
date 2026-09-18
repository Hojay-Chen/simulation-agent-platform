package com.luxera.companion.human.mind;

import com.luxera.companion.human.mind.relationship.BindReason;
import com.luxera.companion.human.mind.relationship.ChatAccountId;
import com.luxera.companion.human.mind.relationship.PersonId;
import com.luxera.companion.human.mind.relationship.PersonObject;
import com.luxera.companion.human.mind.relationship.PersonaSpec;
import com.luxera.companion.human.mind.relationship.Relationship;
import com.luxera.companion.human.mind.relationship.RelationshipGraph;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

/**
 * V2.2 §3.4.6 —— <b>三个概念分开, 通讯录是她自己长出来的</b>。
 *
 * <h2>被钉住的那句话</h2>
 * <blockquote>
 * 她的"通讯录"是<b>她自己长出来的</b>, 不是从聊天平台同步下来的。
 * 这与真人一致 —— 你手机里的备注名是你自己起的, 微信不告诉你这个人是谁。
 * </blockquote>
 *
 * <h2>为什么这些断言非写不可</h2>
 * "同步式"的实现比"自己长式"的实现<b>短得多</b>: 前者只要一个
 * {@code for (好友 : 平台名册) 建成PersonObject()}, 后者要维护
 * "见过 / 认识 / 绑定来源"三层状态。也就是说, 这个设计<b>随时会被简化掉</b> ——
 * 而简化掉之后, 所有已有的测试都还是绿的。所以这里逐条钉住的是
 * <b>"不能少的那些状态"</b>:
 *
 * <ul>
 *   <li>陌生账号查不出人(否则"陌生人"这个状态就不存在了);</li>
 *   <li>见过不等于认识(否则"逐步建立"就没有落脚处);</li>
 *   <li>bootstrap 那一条不算"自己长出来的"(否则那句断言无法被量化验证);</li>
 *   <li>一个人可以有多个账号, 但一个账号只对应一个人。</li>
 * </ul>
 */
class RelationshipGraphBootstrapTest {

    private static final Instant T = Instant.parse("2026-03-02T09:00:00Z");
    private static final Instant T_LATER = Instant.parse("2026-03-05T21:00:00Z");

    private static final ChatAccountId OWNER_ACCOUNT = ChatAccountId.of("account-owner");
    private static final ChatAccountId STRANGER = ChatAccountId.of("account-stranger");

    private static RelationshipGraph bootstrapped() {
        return RelationshipGraph.bootstrap(
                PersonaSpec.owner("阿澈", "恋人", 0.9, 0.8), OWNER_ACCOUNT, T);
    }

    // ─────────────────────── 一、初始化四步 ───────────────────────

    @Test
    @DisplayName("① 创建一个人物对象: 用户")
    void 第一步_用户的人物对象存在() {
        RelationshipGraph graph = bootstrapped();

        PersonObject owner = graph.resolve(OWNER_ACCOUNT).orElseThrow(
                () -> new AssertionError("第 ① 步没做完: 预置的主人查不出来"));

        assertEquals(RelationshipGraph.OWNER, owner.id());
        assertEquals("阿澈", owner.name(), "主人的名字来自 persona —— 不是从平台同步下来的");
        assertTrue(owner.isNamed(), "主人一开始就有名字(装配时给的)");
        assertEquals(T, owner.firstSeenAt(), "她第一次见到主人的时刻必须是她被创建的那一刻");
    }

    @Test
    @DisplayName("② 创建 Relationship, 带上 persona 给的初始亲密值")
    void 第二步_关系与初始值() {
        RelationshipGraph graph = bootstrapped();

        Relationship relationship = graph.relationship(RelationshipGraph.OWNER).orElseThrow(
                () -> new AssertionError("第 ② 步没做完: 关系不存在"));
        assertEquals(0.9, relationship.closeness(), 0.0);
        assertEquals(0.8, relationship.trust(), 0.0);
        assertEquals("恋人", relationship.kind(), "关系说法由 persona 指定 —— 它是一个开放的字符串");
        assertFalse(relationship.isStranger(), "装配时就认识的人不该是陌生人");
    }

    @Test
    @DisplayName("③ 创建绑定: 账号 → 人物对象")
    void 第三步_账号绑定成立() {
        RelationshipGraph graph = bootstrapped();

        assertTrue(graph.knows(OWNER_ACCOUNT), "第 ③ 步没做完: 她不认识主人的账号");
        assertEquals(RelationshipGraph.OWNER, graph.bindings().get(OWNER_ACCOUNT));
        assertEquals(BindReason.KIND_BOOTSTRAP,
                graph.bindReason(OWNER_ACCOUNT).orElseThrow().kind());
    }

    @Test
    @DisplayName("④ 但 bootstrap 那一条不算\"自己长出来的\"")
    void 第四步_装配时给的不算自己长的() {
        RelationshipGraph graph = bootstrapped();

        assertEquals(1, graph.size(), "她认识一个人");
        assertEquals(0, graph.selfMadeBindingCount(),
                "装配时预置的那条绑定被算成了'自己长出来的' —— 于是"
                        + "'她的通讯录有多少是她自己认识的'这个数字永远是 1 起步, 那句话就没法验证了");

        graph.meet(STRANGER, T_LATER);
        graph.promote(STRANGER, BindReason.told("主人介绍说她是他妹妹"), T_LATER);

        assertEquals(2, graph.size(), "现在她认识两个人了");
        assertEquals(1, graph.selfMadeBindingCount(),
                "见过、聊过之后建立的那一条必须被算成'自己长出来的'");
    }

    // ─────────────────────── 二、陌生账号 ───────────────────────

    @Test
    @DisplayName("陌生账号问不出人 —— 这是\"陌生人\"状态的唯一来源")
    void 陌生账号解析为空() {
        RelationshipGraph graph = bootstrapped();

        assertTrue(graph.resolve(STRANGER).isEmpty(),
                "一个从没见过的账号竟然能查出一个人 —— 那说明通讯录是从平台同步来的, "
                        + "而不是她自己长出来的。§3.4.6 的整段设计都在防这件事");
        assertFalse(graph.knows(STRANGER));
        assertTrue(graph.unmetAccounts().isEmpty(), "没见过就是没见过, 连'见过'都还没有");
    }

    @Test
    @DisplayName("第一次见到只是见到, 不是认识")
    void 见到不等于认识() {
        RelationshipGraph graph = bootstrapped();

        PersonId met = graph.meet(STRANGER, T_LATER);

        assertTrue(graph.resolve(STRANGER).isEmpty(),
                "见过一次之后 resolve 就有答案了 —— 于是'她认不认识这个账号'永远回答'认识', "
                        + "而'陌生人'这个状态被删掉了");
        assertFalse(graph.knows(STRANGER));
        assertTrue(graph.seen(STRANGER).isPresent(), "但她确实见过这个人, 这一点必须记得");
        assertTrue(graph.unmetAccounts().contains(STRANGER), "见过的那批里应当有它");
        assertFalse(graph.bindings().containsKey(STRANGER), "见过不该产生绑定");
        assertEquals(1, graph.unnamed().size(),
                "见过的人此刻还没有名字 —— 她的备注名是她自己起的, 平台不会告诉她");
        assertEquals(met, graph.unnamed().get(0).id());
        assertEquals("", graph.unnamed().get(0).name());
    }

    @Test
    @DisplayName("见过两次还是同一个人 —— 否则\"那个总在晚上找我的人\"这个印象会丢")
    void 见过两次还是同一个人() {
        RelationshipGraph graph = bootstrapped();

        PersonId first = graph.meet(STRANGER, T_LATER);
        int sizeAfterFirst = graph.size();
        PersonId second = graph.meet(STRANGER, T_LATER.plusSeconds(3600));

        assertEquals(first, second, "第二次见面造出了新人 —— 她的印象会因此断成两半");
        assertEquals(sizeAfterFirst, graph.size(), "第二次见面不该让她多认识一个人");
    }

    @Test
    @DisplayName("升格用的是当初那个人, 不是新建一个")
    void 升格用当初那个人() {
        RelationshipGraph graph = bootstrapped();

        PersonId met = graph.meet(STRANGER, T_LATER);
        graph.promote(STRANGER, BindReason.inferred("他连着几天晚上都来问同一件事"), T_LATER);

        assertEquals(met, graph.resolve(STRANGER).orElseThrow().id(),
                "升格新建了一个人 —— 见 RelationshipGraph#promote 关于'那个总在晚上找我的人'");
        assertEquals(BindReason.KIND_INFERRED, graph.bindReason(STRANGER).orElseThrow().kind());
        assertEquals(1, graph.selfMadeBindingCount());
        Relationship relationship = graph.relationship(met).orElseThrow();
        assertFalse(relationship.isStranger(), "认都认识了, 关系说法不该还停在'陌生人'");
    }

    // ─────────────────────── 三、账号与人的对应 ───────────────────────

    @Test
    @DisplayName("一个人可以有多个账号(小号)")
    void 一个人可以有多个账号() {
        RelationshipGraph graph = bootstrapped();
        ChatAccountId alt = ChatAccountId.of("account-owner-alt");

        graph.bind(alt, RelationshipGraph.OWNER, BindReason.inferred("她自己认出来的"));

        assertEquals(2, graph.accountsOf(RelationshipGraph.OWNER).size());
        assertEquals(RelationshipGraph.OWNER, graph.resolve(alt).orElseThrow().id());
        assertEquals(1, graph.size(), "换个小号还是同一个人, 不该多出一个人");
    }

    @Test
    @DisplayName("一个账号只对应一个人 —— 否则同一条消息会同时属于两个人")
    void 一个账号只对应一个人() {
        RelationshipGraph graph = bootstrapped();
        ChatAccountId strangerAccount = ChatAccountId.of("account-stranger");
        graph.meet(strangerAccount, T_LATER);

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () ->
                graph.bind(OWNER_ACCOUNT, graph.seen(strangerAccount).orElseThrow().id(),
                        BindReason.told("有人这么说的")));
        assertTrue(error.getMessage().contains("一个账号只对应一个人"),
                "异常信息必须说清楚为什么: " + error.getMessage());
    }

    @Test
    @DisplayName("她也可以认识一个还没有账号的人(现实中的邻居)")
    void 认识一个没有账号的人() {
        RelationshipGraph graph = bootstrapped();

        PersonId neighbor = graph.introduce(T_LATER);

        assertTrue(graph.resolve(ChatAccountId.of("account-nobody")).isEmpty(),
                "没有账号的人在账号表里当然查不到 —— 她认识的是人, 不是账号");
        assertTrue(graph.accountsOf(neighbor).isEmpty(), "这个人一个账号都没有");
        assertFalse(graph.withoutAccount().isEmpty(), "没有账号的人也应当出现在'她认识的人'里");
        assertEquals(2, graph.size(), "她认识两个人: 主人与邻居");
    }

    @Test
    @DisplayName("绑定一个人之前, 那个人必须先存在")
    void 不能凭空绑定() {
        RelationshipGraph graph = bootstrapped();

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () ->
                graph.bind(STRANGER, PersonId.of("person-nobody"), BindReason.told("某人说的")));
        assertTrue(error.getMessage().contains("她心里没有这个人"),
                "异常信息要说清楚: " + error.getMessage());
    }
}
