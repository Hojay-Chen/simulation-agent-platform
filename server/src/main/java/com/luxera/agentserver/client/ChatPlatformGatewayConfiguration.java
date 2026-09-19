package com.luxera.agentserver.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.luxera.companion.world.application.chat.ChatPlatformGateway;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Scope;

import java.time.Instant;

/**
 * V2.2 §6.4 —— 把 {@link HttpChatPlatformGateway} 装配成 bean。
 *
 * <h2>为什么它<b>不是</b>单例</h2>
 *
 * <p>这是本配置里唯一一个真正需要解释的决定, 而它错了的后果是静默的。
 *
 * <p>{@code ChatApplication} 的构造器注释写得很明确: 账号与凭据是<b>账号绑定的</b> ——
 * "一台设备上的聊天应用代表一个聊天账号, 就像她手机上登录的那个号。多账号的方案是
 * <em>装两个应用实例</em>, 而不是一个应用在运行期切换账号"。而 {@link HttpChatPlatformGateway}
 * 里的会话、令牌、WebSocket、补发游标全都是<b>一个账号的</b>状态。
 *
 * <p>于是单例会发生什么, 值得写下来: {@code connect()} 的第一件事是 {@code openSocket()},
 * 而它开头就 {@code closeSocket()} —— 这是"重连"该有的行为(两条并存的连接会让同一条消息
 * 响两次)。可如果两个伴侣共用这一个 bean, 那么<b>B 登录的那一刻会把 A 的连接关掉</b>,
 * 而 A 那边除了日志里一句"长连接关闭"之外没有任何表现 —— {@code connected()} 全仓零调用,
 * A 的 {@code ChatApplication} 也不会因此收到任何回调。她会从此收不到信号, 而
 * "她没有新消息"与"她聋了"在界面上完全一样。
 *
 * <p>所以作用域是 {@code prototype}: <b>一个伴侣一条腿</b>。
 *
 * <h2>prototype 的代价, 写在需要它的地方</h2>
 *
 * <p>Spring <b>不</b>对 prototype bean 调用销毁方法(那是单例才有的待遇)。所以
 * {@link HttpChatPlatformGateway#close()} 不会因为容器关闭而被自动调用 —— 而它要停的是
 * 一条定时线程与一条长连接。两件事各自的性质让这个漏不至于致命: 线程是 daemon 的
 * (不会阻止 JVM 退出), 而连接会随进程一起消失。但<b>伴侣被卸载而进程还在跑</b>的那种
 * 卸载, 必须由装配方显式调 {@code close()} —— 否则那条腿会一直重连、一直刷日志,
 * 而它已经没有任何人在听了。那句话属于装配代码, 这里只能记着。
 *
 * <h2>它不依赖启动顺序</h2>
 *
 * <p>造一个 bean 不会开连接: 套接字只在 {@link ChatPlatformGateway#connect} 里建立,
 * 而那由 {@code chat.login} 能力触发。所以本配置<b>不会</b>让 8091 在聊天平台没起来时
 * 启动失败 —— 这正是想要的: 聊天平台挂了不该让仿真进程起不来, 该让"她登录不上"这件事
 * 在她真的要聊天的时候才暴露出来。
 *
 * <h2>时钟</h2>
 *
 * <p>用 {@code Instant::now}(墙上时钟)。这与 {@code ChatApplication} /
 * {@code AudioSystem} / {@code ScreenSystem} 是同一个已知妥协: 仿真时间在那三层
 * (human/world/boundary)一律由调用方传入, 而<b>适配器层没有仿真时刻可传</b> ——
 * "这条腿断了多久"只有墙上时钟答得出来。
 *
 * <p>它<b>不</b>做成一个可注入的 bean, 两个理由:
 * <ul>
 *   <li>没有第二个消费者。{@code ChatApplication} 不是 Spring 造出来的(装配代码
 *       {@code new} 它), 所以给它一个 bean 也不会被用上 —— 而"实现了但零调用"正是
 *       契约 1.0.1 删掉那两个 provision 端口的原因。等装配真的存在了, 由它决定
 *       是共享一个时钟还是各拿各的;</li>
 *   <li>不存在"两个时钟漂移"的问题。{@code Instant::now()} 读的是同一个系统时钟 ——
 *       它不会与它自己不一致。需要注入的唯一理由是<b>测试里要一个定住的时刻</b>,
 *       而那个入口在构造器上({@code Supplier<Instant>}), 测试直接 new 一个就够了。
 * </ul>
 */
@Configuration
public class ChatPlatformGatewayConfiguration {

    /**
     * 一个伴侣一条腿 —— 见类注释"为什么它不是单例"。
     *
     * <p>{@code destroyMethod = ""} 是<b>刻意写下的空串</b>, 而不是省略: 默认值
     * {@code INFER_METHOD} 会让 Spring 去找一个叫 {@code close} 的方法并调用它 ——
     * 对 prototype 它本来就不会调, 但留下那个"看起来会被管理"的默认值, 会让下一个读的人
     * 以为生命周期有人管。写成空串的意思是"这里没有人管, 装配方自己管"。
     */
    @Bean(destroyMethod = "")
    @Scope("prototype")
    ChatPlatformGateway chatPlatformGateway(
            @Value("${app.chat-platform.base-url:http://127.0.0.1:8081}") String chatBaseUrl,
            @Value("${app.chat-platform.timeout-ms:5000}") int timeoutMs,
            @Value("${app.chat-platform.reconnect-attempts:12}") int reconnectAttempts,
            @Value("${app.chat-platform.reconnect-delay-ms:1000}") long reconnectDelayMs,
            @Value("${app.chat-platform.ping-interval-ms:30000}") long pingIntervalMs,
            ObjectMapper objectMapper) {
        return new HttpChatPlatformGateway(chatBaseUrl, objectMapper, Instant::now,
                timeoutMs, reconnectAttempts, reconnectDelayMs, pingIntervalMs);
    }
}
