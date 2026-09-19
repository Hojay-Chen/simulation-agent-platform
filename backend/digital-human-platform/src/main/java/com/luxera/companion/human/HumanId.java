package com.luxera.companion.human;

import java.util.Objects;

/**
 * V2.2 §3.1.1 —— <b>她是谁</b>。一个值类型, 不是一个 {@code String}。
 *
 * <h2>为什么值得为它单独写一个类</h2>
 *
 * 这个系统里有<b>至少五个不同的 ID</b>：
 * <ul>
 *   <li>{@code HumanId}（她）；</li>
 *   <li>{@code userId}（谁拥有她 —— §3.6.3 的归属, 刻意不在 {@code human} 包里）；</li>
 *   <li>{@code ChatAccountId}（聊天平台上的一个账号）；</li>
 *   <li>{@code PersonId}（她心里的一个人 —— 那是<b>她的</b>知识, 不是世界的账号）；</li>
 *   <li>{@code PlanItemId}（计划表上的一项）。</li>
 * </ul>
 *
 * 它们在数据库里<b>全都是 {@code VARCHAR(36)}</b>。也就是说, 把 {@code userId} 传进一个
 * 要 {@code humanId} 的位置, 编译器一句话都不会说, 而运行时的表现是
 * <b>"她读到了别人的记忆"</b> —— 一个不会抛异常、不会写日志、只在某一天被人发现的错误。
 *
 * <p>五个 record 各自包一层, 是让这一类错误在<b>编译期</b>变成类型错误。
 * 代价是每个边界上多一次 {@code .value()}, 而这个代价在第一次拦下一个错参数时就还清了。
 *
 * <h2>校验放在紧凑构造器里, 而且消息里带收到的值</h2>
 *
 * 空值与空白在这里就死掉, 而不是等到 {@code VARCHAR(36)} 那一列报一个
 * "违反非空约束" —— 后者发生在一次数据库往返之后, 且日志里只有 SQL, 没有"是谁传的"。
 *
 * <p>消息里带上<b>收到的原值</b>（见下面两处 {@code + value}）是刻意的:
 * 一个 {@code HumanId} 的构造点通常在做装配（"把她从库里读出来"、"给请求参数包一层"），
 * 而那时手里只有这个字符串。消息里不带它, 排查的人就得回去看调用栈;
 * 带了它, 一眼就能看出"哦, 传进来的是那个用户的 id"。
 *
 * <h2>它不负责什么</h2>
 * <ul>
 *   <li><b>不校验格式</b>。§3.1.1 说"格式与 {@code agent_ownership.human_id} 一致",
 *       但那条约束属于那一列的 schema, 不属于这个值类型 —— 在这里写一遍正则,
 *       就等于同一个规则有两个实现, 而它们会漂移。</li>
 *   <li><b>不生成</b>。这里<b>没有</b> {@code generate()}（{@code PlanItemId} 与
 *       {@code ActivityId} 有）。理由: 那些 id 是她<b>做事的那一刻</b>造出来的,
 *       必须能在内存里发号; 而她的 id 是<b>建号时</b>由平台分配的, 那一刻的正确来源
 *       是数据库那一列, 不是这里的计数器。加一个 {@code generate()} 只会给人
 *       一条"随手造一个她"的捷径。</li>
 * </ul>
 *
 * @param value 那个 id 的字符串形式, 非空非空白
 */
public record HumanId(String value) {

    public HumanId {
        Objects.requireNonNull(value, "HumanId 不能为空 —— 一个没有 id 的她无法被任何一张表引用。"
                + "收到的值: null");
        if (value.isBlank()) {
            throw new IllegalArgumentException("HumanId 不能是空白 —— 空白 id 在数据库里能与任何东西"
                    + "比较相等, 于是'她'会与另一个'她'混成一个人。收到的值: [" + value + "]");
        }
    }

    /** 与 §3.1.1 的签名一致。它与构造器是同一件事, 只是读起来像在"包一层"。 */
    public static HumanId of(String value) {
        return new HumanId(value);
    }

    /**
     * 从数据库读回来的那一列。
     *
     * <p>它与 {@link #of(String)} 完全等价 —— 存在只为了在装配代码里把意图写出来:
     * <b>"这一列就是 id, 不是我随手拼的一个字符串"</b>。日志里两者都会出现, 而名字
     * 让读代码的人少想一步。
     */
    public static HumanId parse(String raw) {
        return new HumanId(raw);
    }

    /** 原样返回 —— 日志与拼接 key 时不必到处写 {@code .value()}。 */
    @Override
    public String toString() {
        return value;
    }
}
