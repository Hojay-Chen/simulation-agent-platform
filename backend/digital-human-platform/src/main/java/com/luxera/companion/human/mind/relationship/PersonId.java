package com.luxera.companion.human.mind.relationship;

import java.util.Objects;

/**
 * V2.2 §3.4.6 —— <b>她心里的"一个人"的标识</b>。
 *
 * <h2>为什么它不是 {@link ChatAccountId}, 也不是它的包装</h2>
 * 因为这两样东西的<b>生命周期不一样</b>, 而把生命周期不同的两个标识合成一个,
 * 是这一类系统最常见的一处事故:
 *
 * <ul>
 *   <li>一个人可以<b>同时有两个账号</b>(小号)。如果 {@code PersonId} 就是账号,
 *       那"她认识的是同一个人"这件事在数据上表达不出来 —— 她会把对方当成两个人,
 *       于是"我们昨天不是聊过吗"这句话她会说错;</li>
 *   <li>一个人可以<b>还没有账号</b>(现实里的邻居、同学)。如果 {@code PersonId} 只能是账号,
 *       那这些人在她心里就无法存在 —— 而 §3.4.6 明确说"她也可能认识一个还没有聊天账号的人";</li>
 *   <li>账号可以<b>换、可以注销</b>。换了号之后她还是她, 但账号不是那个账号了。</li>
 * </ul>
 *
 * <p>所以 {@code PersonId} 是<b>她给的</b>身份, {@link ChatAccountId} 是<b>平台给的</b>身份,
 * 两者由 {@link RelationshipGraph} 绑定。绑定这件事本身就是 §3.4.6 要她"自己维护"的东西。
 *
 * <h2>它刻意不做什么</h2>
 * <ul>
 *   <li><b>不含任何关于这个人的信息</b>。名字、印象、关系都在
 *       {@link PersonObject} 与 {@link Relationship} 里。往 id 里塞信息(比如
 *       {@code "friend-8f3a"})会让"关系变了"变成一次改 id 的操作,
 *       而改 id 意味着所有指向她的记忆都要跟着改 —— 那些记忆不该知道关系变过;</li>
 *   <li><b>不生成自己</b>。没有 {@code PersonId.random()}(也就没有隐式的时钟与随机源),
 *       只有一个要求她显式给出的工厂。</li>
 * </ul>
 *
 * <p><b>与文档的分歧</b>: §3.4.6 的示例代码没有给出 {@code PersonId} 的形态。
 * 这里选 record 包一个字符串, 而不是 {@code long} 或 UUID —— 理由是她认识的人
 * 一双手数得过来, 可读性比空间重要, 而日志里出现 {@code person:邻居王阿姨}
 * 比 {@code person:4711} 有用得多。
 */
public record PersonId(String value) implements Comparable<PersonId> {

    public PersonId {
        Objects.requireNonNull(value, "人的标识不能为空");
        if (value.isBlank()) {
            throw new IllegalArgumentException(
                    "人的标识不能是空白 —— 一个空白的标识会让两个素不相识的人在她心里变成同一个人");
        }
    }

    public static PersonId of(String value) {
        return new PersonId(value);
    }

    public boolean sameAs(PersonId other) {
        return other != null && value.equals(other.value);
    }

    @Override
    public int compareTo(PersonId other) {
        return value.compareTo(other.value);
    }

    public String describe() {
        return "person:" + value;
    }

    @Override
    public String toString() {
        return value;
    }
}
