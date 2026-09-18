package com.luxera.companion.world.application.chat;

import java.time.Instant;
import java.util.Objects;

/**
 * V2.2 §6.3 / §6.4 —— <b>聊天应用的一次登录会话</b>: 它的聊天账号 + 令牌 + 有效期。
 *
 * <h2>它是"授权三层"里第 ② 层的实体</h2>
 * {@code ChatPlatformGateway} 的类注释里写了三层授权:
 * <pre>
 *   ① 平台授权     —— 谁能访问这台服务器(JWT / API Key)      ← 与她无关
 *   ② 应用会话     —— <b>本 record</b>: 聊天账号登录后拿到的 token
 *   ③ Agent 能力授权 —— 这个 agent 能不能执行这个 Action      ← 在 ActionFabric 那一侧
 * </pre>
 * 第 ② 层存在的全部意义是那句 V2.1 §7.1.2 的强化:
 * <blockquote>
 *   不能因为 Agent 是系统内部对象, 就绕过聊天平台权限。
 * </blockquote>
 * 也就是说, <b>这个 token 不是装饰</b>: 聊天应用发的每一条请求都要带上它,
 * 而服务器按它判定"这个账号能不能看这条会话"。一个"我是内部的所以直读数据库"的捷径
 * 会让这一层消失, 而消失的后果是: 她能看到<b>任何</b>聊天账号的会话,
 * 包括她本来不该看到的那些。
 *
 * <h2>令牌绝不进日志</h2>
 * {@link #describe()} 刻意<b>不</b>打印 token, 只打印账号 id 与有效期余量。
 * 这类字段一旦进了日志, 它会出现在诊断面板、错误上报、甚至截图里 ——
 * 而在这个系统里日志是被<b>大量阅读</b>的(行为分析、因果追踪都读它)。
 * 让"不打印敏感字段"成为一条需要人记住的纪律是不可靠的, 所以这里把它变成
 * "那个字段根本不在这个方法里"。
 *
 * @param accountId 这个会话属于哪个聊天账号。注意它是<b>账号 id</b>(如 {@code agent_m3k9}),
 *                  不是人的名字 —— "这个人叫什么"是她自己的知识, 见 §6.5
 * @param token     访问令牌。<b>不要把它写进任何事件载荷或日志</b>
 * @param issuedAt  什么时候拿到的
 * @param expiresAt 什么时候失效。到点后聊天应用必须重新登录, 而不是继续用一个过期令牌 ——
 *                  继续用的表现是"她发的消息全部石沉大海", 而日志里只有一串 401
 */
public record ChatSession(String accountId, String token, Instant issuedAt, Instant expiresAt) {

    public ChatSession {
        if (accountId == null || accountId.isBlank()) {
            throw new IllegalArgumentException(
                    "会话必须知道它属于哪个聊天账号 —— 没有账号的会话无法做任何权限判定, "
                            + "而'没有权限判定的会话'就是我们要消灭的那个后门");
        }
        if (token == null || token.isBlank()) {
            throw new IllegalArgumentException("会话必须有令牌 —— 空令牌会让每一次调用都变成匿名访问");
        }
        Objects.requireNonNull(issuedAt, "会话必须带签发时刻 —— 仿真时钟下不许读墙上时钟");
        Objects.requireNonNull(expiresAt, "会话必须带失效时刻 —— 永不过期的令牌无法被主动作废");
        if (expiresAt.isBefore(issuedAt)) {
            throw new IllegalArgumentException(
                    "失效时刻(" + expiresAt + ")早于签发时刻(" + issuedAt + "), 这是一个生下来就过期的会话");
        }
    }

    public static ChatSession of(String accountId, String token, Instant issuedAt, Instant expiresAt) {
        return new ChatSession(accountId, token, issuedAt, expiresAt);
    }

    /**
     * 续期 —— 服务端换了一个新令牌, 其余不变。
     *
     * <p>刻意做成一个方法而不是让调用方 new 一个: 续期时 {@code accountId} 与
     * {@code issuedAt} 必须跟着变, 而<b>忘掉其中一个</b>的后果是"会话看起来还是同一个",
     * 但它的权限判定依据已经被悄悄换掉了。
     */
    public ChatSession renewed(String newToken, Instant now, Instant newExpiry) {
        return new ChatSession(accountId, newToken, now, newExpiry);
    }

    /** 到点了没有。 */
    public boolean expiredAt(Instant now) {
        Objects.requireNonNull(now, "判断会话是否过期必须给一个时刻");
        return !now.isBefore(expiresAt);
    }

    /** 还剩多久 —— 诊断面板与"快到期了要不要提前续"的判断都用它。 */
    public java.time.Duration remainingAt(Instant now) {
        Objects.requireNonNull(now, "计算剩余有效期必须给一个时刻");
        return java.time.Duration.between(now, expiresAt);
    }

    /**
     * 一行摘要 —— <b>可以安全地打进日志</b>。
     *
     * <p>它不包含 token, 见类注释。这条纪律与本设计里
     * {@code NotificationRequest} 不含正文是同一个手法:
     * <b>让"不该泄露的东西"根本不在这个类型的输出里</b>, 而不是靠人记得别打它。
     */
    public String describe() {
        return "chat-session[" + accountId + " 有效期至 " + expiresAt + "]";
    }

    /** 覆盖 toString —— record 默认会打印 token, 而那正是我们不想看到的。 */
    @Override
    public String toString() {
        return describe();
    }
}
