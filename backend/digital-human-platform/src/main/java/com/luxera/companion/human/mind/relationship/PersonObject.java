package com.luxera.companion.human.mind.relationship;

import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

/**
 * V2.2 §3.4.6 —— <b>她心里的"一个人"</b>: 有名字、有印象、有标签。
 *
 * <h2>它为什么必须与 {@code ChatAccountId} 分开</h2>
 * 因为 §3.4.6 的产品承诺是"她的通讯录是她自己长出来的, 不是从聊天平台同步下来的"。
 * 这条承诺在代码里的落点就是这张表:
 *
 * <table border="1">
 *   <tr><th></th><th>谁给的</th><th>会不会被平台改掉</th></tr>
 *   <tr><td>{@code ChatAccountId}</td><td>聊天平台</td><td>会(改号、注销)</td></tr>
 *   <tr><td>{@code PersonObject.name}</td><td><b>她自己</b></td><td><b>不会</b></td></tr>
 * </table>
 *
 * <p>所以 {@link #name()} 是<b>备注名</b>, 不是平台昵称。一个人可以在平台叫
 * "小明"，而在她心里叫"那个总在晚上找我的人" —— 后者才是她真正用来想起对方的东西。
 * 如果把平台昵称存进来, 那么"她认识了一个人"这件事就退化成了"平台告诉了她一个字符串"。
 *
 * <h2>{@code name} 可以为空 —— 这是刻意的, 不是漏了校验</h2>
 * 因为"她见过一个陌生账号"这件事是真实存在的状态, 而它在数据上长这样:
 * {@code PersonObject(id=person-3, name="", ...)}。她的第一反应是"这是谁?"——
 * 这个"不知道"必须能被表达出来, 否则实现者只能填一个占位名(比如 {@code "未知"}),
 * 而占位名一旦写进去, 就再也没法区分"她给这个人起名叫未知"与"她根本还不知道他是谁"。
 *
 * <p>用 {@link #isNamed()} 判断, 而不是去比较 {空串}。
 *
 * <h2>它不负责什么</h2>
 * <ul>
 *   <li><b>不持有账号</b>。账号绑在 {@link RelationshipGraph} 的绑定表里。放一个
 *       {@code Set<ChatAccountId>} 进来会让"一个有多个账号的人"在复制对象时出现两份
 *       不一致的账号列表 —— 而那正是 §3.4.6 要避免的"两个地方都能改同一个事实";</li>
 *   <li><b>不持有关系</b>。亲密度、信任在 {@link Relationship} 里。
 *       放进这里会让"她与这个人是什么关系"依附于"她记不记得这个人",
 *       于是删掉一个人格对象会连关系一起删掉;</li>
 *   <li><b>不生成印象</b>。{@link #impression()} 是认知环节算出来之后<b>传进来</b>的。
 *       在这里做一个默认印象会让每个陌生人都自带一句评价。</li>
 * </ul>
 */
public record PersonObject(
        PersonId id,
        String name,
        String impression,
        Set<String> tags,
        Instant firstSeenAt,
        Instant updatedAt) {

    public PersonObject {
        Objects.requireNonNull(id, "人物对象必须有身份 —— 见 PersonId: 它是她给的, 不是平台给的");
        Objects.requireNonNull(firstSeenAt, "必须记下她第一次见到这个人的时刻 —— 不许读系统时钟");
        Objects.requireNonNull(updatedAt, "必须有更新时刻 —— 不许读系统时钟");
        name = name == null ? "" : name.trim();
        impression = impression == null ? "" : impression.trim();
        tags = tags == null ? Set.of() : Set.copyOf(tags);
        if (updatedAt.isBefore(firstSeenAt)) {
            throw new IllegalArgumentException(
                    "更新时刻 " + updatedAt + " 早于初见时刻 " + firstSeenAt
                            + " —— 这条记录会让" + "她什么时候认识他的" + "这个问题答错");
        }
    }

    /**
     * 一个<b>她还不认识</b>的人 —— 她只知道有这么个账号在联系她。
     *
     * <p>这是 {@link RelationshipGraph#meet} 的产物, 也是"陌生账号"这个词在数据上的样子。
     */
    public static PersonObject unnamed(PersonId id, Instant at) {
        return new PersonObject(id, "", "", Set.of(), at, at);
    }

    public static PersonObject of(PersonId id, String name, Instant at) {
        return new PersonObject(id, name, "", Set.of(), at, at);
    }

    public static PersonObject of(PersonId id, String name, String impression, Set<String> tags,
                                  Instant firstSeenAt, Instant updatedAt) {
        return new PersonObject(id, name, impression, tags, firstSeenAt, updatedAt);
    }

    /** 她给这个人起了个名字(或改了备注名)。 */
    public PersonObject named(String newName, Instant at) {
        return new PersonObject(id, newName, impression, tags, firstSeenAt, at);
    }

    /** 换一句印象 —— 由认知环节算好了传进来, 这里不做任何生成。 */
    public PersonObject withImpression(String newImpression, Instant at) {
        return new PersonObject(id, name, newImpression, tags, firstSeenAt, at);
    }

    public PersonObject withTag(String tag, Instant at) {
        Set<String> merged = new LinkedHashSet<>(tags);
        merged.add(tag);
        return new PersonObject(id, name, impression, merged, firstSeenAt, at);
    }

    public PersonObject withoutTag(String tag, Instant at) {
        Set<String> remaining = new LinkedHashSet<>(tags);
        remaining.remove(tag);
        return new PersonObject(id, name, impression, remaining, firstSeenAt, at);
    }

    /**
     * 她能不能叫得出这个人的名字。
     *
     * <p>所有"她该怎么称呼对方"的判断都走它, 而不是去比较空串 ——
     * 后者会在下一次有人改成 {@code null} 或 {@code " "} 时静默失效。
     */
    public boolean isNamed() {
        return !name.isEmpty();
    }

    public boolean hasImpression() {
        return !impression.isEmpty();
    }

    public String describe() {
        return (isNamed() ? name : "一个还不认识的人")
                + (hasImpression() ? " (" + impression + ")" : "")
                + (tags.isEmpty() ? "" : " " + tags);
    }

    @Override
    public String toString() {
        return describe();
    }
}
