package com.luxera.companion.human.mind.relationship;

import java.util.Objects;

/**
 * V2.2 §3.4.6 —— <b>聊天平台上的一个账号标识</b>。文档的原话: <b>它就是一个字符串</b>。
 *
 * <h2>与文档的分歧: 它本该在 World, 现在在 Human</h2>
 * §3.4.6 的表格把 {@code ChatAccountId} 归给 <b>World</b>, 而本类在
 * {@code human.mind.relationship} 下。这是<b>代码与文档冲突、以代码为准</b>的那一处,
 * 所以把理由写在这里:
 *
 * <ol>
 *   <li><b>架构守卫不允许别的做法</b>。{@code V22BoundaryArchitectureTest} 断言
 *       {@code human/**} 不依赖 {@code world/**}(验收标准 F)。她要用账号<->人物的绑定
 *       来回答"这个找我的人是谁", 而绑定表的键就是这个类型 —— 它若定义在
 *       {@code world/}, 这条断言立刻变红;</li>
 *   <li><b>它确实不是"世界的一部分"</b>。World 侧拿它当什么? 看
 *       {@code ActionCommand} 的形态就知道: 那边的账号是一个裸的 {@code String},
 *       放在 {@code arguments} 里。也就是说, <b>World 从未需要一个叫
 *       {@code ChatAccountId} 的类型</b> —— 它只需要一个字符串。真正需要"这是个标识,
 *       不是一个随便的字符串"的是她这一侧: 她要拿它当键去查通讯录;</li>
 *   <li>于是分工是干净的: <b>同一个字符串, 两边各自用自己的形态</b>。World 侧是
 *       {@code String}(它只负责传), Human 侧是这个 record(它负责被查)。两边
 *       没有共享类型, 也就不需要 {@code human} 依赖 {@code world}。</li>
 * </ol>
 *
 * <h2>它刻意不做什么 —— 这一段是本节最重要的约束</h2>
 * 它<b>不包含昵称、头像、备注、手机号</b>, 也不提供任何"取这个账号的资料"的方法。
 * 因为那正是 §3.4.6 的产品承诺要防的事:
 *
 * <pre>{@code
 * // ❌ 如果 ChatAccountId 携带昵称, 下面这条捷径随时会被写出来:
 * String name = percept.source().account().nickname();   // "她"根本没在认识人, 只是抄了平台的备注
 * // ✅ 她的通讯录是她自己长出来的:
 * String name = graph.resolve(account).map(PersonObject::name).orElse("一个不认识的人");
 * }</pre>
 *
 * <p>前者的后果不是"数据不准", 而是<b>整个 §3.4.6 的设计被绕过</b>:
 * 通讯录变成了聊天平台的投影, 而"她自己建立认识"这件事再也不会发生 ——
 * 因为不需要发生。真人不会因为对方改了微信昵称就觉得"这个人变了",
 * 而一个读平台昵称的实现会。
 */
public record ChatAccountId(String value) implements Comparable<ChatAccountId> {

    public ChatAccountId {
        Objects.requireNonNull(value, "账号标识不能为空");
        if (value.isBlank()) {
            throw new IllegalArgumentException(
                    "账号标识不能是空白 —— 空白的账号会在绑定表里撞成同一个键, "
                            + "于是两个素不相识的人在她心里变成同一个人");
        }
    }

    public static ChatAccountId of(String value) {
        return new ChatAccountId(value);
    }

    public boolean sameAs(ChatAccountId other) {
        return other != null && value.equals(other.value);
    }

    @Override
    public int compareTo(ChatAccountId other) {
        return value.compareTo(other.value);
    }

    public String describe() {
        return "account:" + value;
    }

    @Override
    public String toString() {
        return value;
    }
}
