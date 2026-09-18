package com.luxera.companion.world.application;

import com.luxera.companion.boundary.action.Capability;
import com.luxera.companion.world.device.Device;

import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * V2.2 §4.4 —— <b>装在设备上的应用</b>: 用户那句"让三方平台软件自己实现接口"的落点。
 *
 * <h2>用户的原话</h2>
 * <blockquote>
 *   手机对象还可以有"手机应用"这个类对象, 实现一个表示"手机应用"的接口,
 *   然后把要让 agent 能够使用的三方平台软件, 让他们自己来实现这个"手机应用"接口的实现类,
 *   去对接他们的软件 api, <b>包括我们自己的聊天平台也是同样道理</b>。
 * </blockquote>
 * 最后半句是本接口全部设计约束里最重要的一条: <b>平台自带的聊天应用与第三方应用走
 * 完全相同的那一个接口</b>。一旦平台给自己留一条内部捷径(比如一个能直接访问
 * Mind 的后门), 那个接口就再也不能证明"第三方能做到" —— 而"第三方能做到"
 * 是 P6(平台提供默认实现但不垄断扩展点)的全部意义。
 *
 * <h2>为什么接口长成这五件事</h2>
 * <table border="1">
 *   <tr><th>方法</th><th>为什么必须有</th><th>不做会怎样</th></tr>
 *   <tr>
 *     <td>{@link #id()}</td>
 *     <td>设备按它记账、日志用它定位、能力冲突时用它分辨来源</td>
 *     <td>两个应用的能力 key 撞车时无法回答"是谁注册的", 而
 *         {@code CapabilityRegistry} 保留先注册的那个 —— 于是装配顺序决定了谁会响应</td>
 *   </tr>
 *   <tr>
 *     <td>{@link #descriptor()}</td>
 *     <td>它要显示在她的手机上、出现在诊断面板里、决定她用哪段通知音</td>
 *     <td>第三方应用就没有"自我介绍"的地方, 而"她手机上装了什么"这件事只能靠
 *         id 字符串猜</td>
 *   </tr>
 *   <tr>
 *     <td>{@link #capabilities()}</td>
 *     <td><b>Agent 能对这个应用做什么</b> —— 它就是用户的 Skill / Tool / MCP 清单的来源</td>
 *     <td>应用装了但 agent 用不了, 那装它干什么</td>
 *   </tr>
 *   <tr>
 *     <td>{@link #onAttach(Device)}</td>
 *     <td>应用要知道自己装在哪台设备上, 才能在建连接、推送、缓存时做对的事</td>
 *     <td>它只能在自己的构造器里猜"我大概在一台手机上", 而真机可能是平板或车机</td>
 *   </tr>
 *   <tr>
 *     <td>{@link #onDetach()}</td>
 *     <td>释放连接的<b>唯一时机</b> —— WebSocket、定时器、线程池</td>
 *     <td>卸载的应用留下一条永不断开的连接, 而它会继续把一个已经不存在的应用的通知
 *         推进世界</td>
 *   </tr>
 * </table>
 *
 * <h2>为什么<b>没有</b> {@code ApplicationType} 枚举</h2>
 * 因为"世界上有哪几类应用"正是 P4 禁止用枚举表达的东西。加一个"宠物喂食器应用"
 * 不该需要修改平台源码并重新编译 —— 而这个接口的存在就是为了让那件事不发生。
 * 应用之间的差别体现在三处<b>数据</b>上, 而不是一个类型标签上:
 * <ol>
 *   <li>{@link #id()} —— 它是谁; </li>
 *   <li>{@link #descriptor()} —— 它怎么介绍自己; </li>
 *   <li>{@link #capabilities()} —— 它能做什么。</li>
 * </ol>
 * 一个"外卖应用"与"聊天应用"的差别, 就是它们贡献的能力不同、通知音不同, 如此而已。
 *
 * <h2>为什么 {@code onAttach} 的参数是 {@link Device} 而不是 {@code Phone}</h2>
 * 因为应用装在<b>设备</b>上, 而设备可以是手机、音箱、车载屏、冰箱。
 * 参数写成 {@code Phone} 会让"给音箱写一个音乐应用"变成一次接口改造 ——
 * 而那正是用户说的"三方自己实现"最不愿意遇到的事。
 *
 * <p>反过来, {@code Device} 上并没有"读聊天记录"之类的方法 ——
 * 应用想暴露什么, 由它自己的 {@link #capabilities()} 说了算。
 * 这条<b>不对称</b>是刻意的: 设备对应用的接口很小(只够它知道自己在哪),
 * 应用对 agent 的接口由应用自己定义。
 *
 * <h2>为什么 {@code capabilities()} 返回 {@link Capability} 而不是别的</h2>
 * 因为{@code boundary/action} 里的 {@code Capability} 已经是 Skill / Tool / MCP
 * 三种说法的统一表示(见 {@code CapabilityDescriptor} 的类注释)。
 * 应用若再定义一套自己的"工具"类型, 平台就必须写一层翻译 ——
 * 而<b>两套表示之间的翻译层迟早会漂移</b>, 表现是"描述里有的参数, 执行时不认"。
 *
 * <h2>一个第三方要接入, 具体要做什么</h2>
 * <pre>{@code
 * public final class FeederApplication implements DeviceApplication {
 *     private Device host;
 *
 *     @Override public String id() { return "petfeeder"; }
 *
 *     @Override public Descriptor descriptor() {
 *         return Descriptor.of("喂食器", "1.4.2")
 *                 .description("远程投喂与粮仓余量")
 *                 .notificationSound("feeder-notify");
 *     }
 *
 *     @Override public Collection<Capability> capabilities() {
 *         return List.of(new FeedNowCapability(this::httpPost));  // 对接自己的 API
 *     }
 *
 *     @Override public void onAttach(Device device) { this.host = device; }
 *
 *     @Override public void onDetach() { this.host = null; closeHttpClient(); }
 * }
 * }</pre>
 * 关键点: <b>它没有 import 平台里的任何具体设备类</b>, 也没有被平台 import ——
 * 平台在装配时才知道它的存在(通过配置或 SPI), 而它贡献的能力会自动出现在
 * agent 的工具清单里({@code CapabilityRegistry.allDescriptors()})。
 */
public interface DeviceApplication {

    /**
     * 应用的稳定标识 —— 例如 {@code "chat"}、{@code "calendar"}。
     *
     * <p>它会被写进 {@code NotificationRequest.applicationKey}, 因此它同时是
     * <b>通知的标签</b>。要求跨重启不变: 设备按它记未读数, 改 id 等于换一个应用。
     *
     * <p>语法上与 {@code CapabilityDescriptor} 的 key 片段一致(小写、短横线):
     * {@code id} 会成为 {@code <id>.something} 这类能力 key 的命名空间,
     * 而能力 key 有语法校验, 于是"id 里带大写"这种问题会在注册能力时报错 ——
     * 比在运行到通知那一步才报错要早得多。
     */
    String id();

    /** 应用怎么介绍自己 —— 显示在手机上, 也显示在诊断面板里。 */
    Descriptor descriptor();

    /**
     * 这个应用对 agent 暴露的能力。
     *
     * <p>它会被 {@code Phone} 原样并进设备的能力清单 —— 手机<b>不认识</b>这些能力,
     * 也不需要认识(见 {@code Device.capabilities()} 的说明)。
     *
     * <p>实现应当返回<b>稳定的</b>集合: 同一个应用实例每次调用返回等价的能力。
     * 每次新建一个 {@code Capability} 会让基于身份的去重失效, 而
     * {@code CapabilityRegistry} 的重复注册判定正是按 key 做的 —— 后果是
     * "重新装一次应用, 能力表里多了一份"。
     */
    Collection<Capability> capabilities();

    /**
     * 装到设备上了。
     *
     * <p>调用时机由 {@code Phone.install} 决定, 且<b>保证在记账之后</b> ——
     * 也就是说, 应用在自己的 {@code onAttach} 里问"这台设备上有哪些应用"时,
     * 得到的是包含它自己的答案。反过来会让"我是不是第一个装上的"这类判断出错。
     *
     * <p>实现<b>不要</b>在这个方法里做慢操作(网络握手、加载大文件):
     * 装配是同步路径, 而这里的一次 3 秒等待会出现在"她第一次拿手机"的那一刻。
     * 后台连接应当在 {@code onAttach} 里<b>启动</b>, 不在 {@code onAttach} 里<b>等完</b>。
     *
     * @param device 装在哪台设备上。接口类型而不是具体设备类 —— 见类注释
     */
    void onAttach(Device device);

    /**
     * 从设备上摘下来了 —— <b>释放资源的唯一时机</b>。
     *
     * <p>实现必须保证自己是幂等的: 卸载流程在设备关机、装配回滚、测试拆卸时
     * 都可能被调用第二次, 而"关闭一个已经关掉的连接"不该抛异常。
     *
     * <p>它<b>不</b>返回成功与否: 卸载已经发生(设备那一侧已经把账销了),
     * 这里返回 false 也无法撤销。所以调用方只会记录异常, 不会因为这里失败而回滚。
     */
    void onDetach();

    // ═══════════════════════════ 自描述 ═══════════════════════════

    /**
     * 应用的自描述。
     *
     * <h2>为什么 {@code notificationSound} 在这里, 而不在手机的音频系统里</h2>
     * 因为"聊天软件用哪段提示音"是<b>应用的</b>属性: 她一听就知道是哪个软件在响。
     * 手机负责的是"用多大声放"(<b>通道音量</b>), 应用负责的是"放哪一段"。
     * 这个分工在 {@code NotificationRequest.soundProfile} 上收口 ——
     * 手机因此不需要认识任何应用, 却仍然能放出正确的音色。
     *
     * @param applicationKey 稳定标识 —— 与 {@link DeviceApplication#id()} 相同。
     *                       冗余存一份是为了让描述本身可以独立于应用实例传递
     *                       (诊断面板、事件载荷都只要描述)
     * @param displayName    给人看的名字
     * @param version        版本号, 自由格式字符串。<b>不做语义化版本校验</b> ——
     *                       应用来自第三方, 而平台替它校验版本格式不会让任何事情变好
     * @param description    一句话说明这个应用是干什么的
     * @param notificationSound 通知音标识; 空表示用平台默认音
     * @param tags           自由标签, 例如 {@code List.of("社交", "系统")}。
     *                       <b>刻意是字符串列表而不是枚举</b>: 标签会随生态生长,
     *                       而"它长什么样"不该由平台预先决定(P4)
     */
    record Descriptor(String applicationKey,
                      String displayName,
                      String version,
                      String description,
                      String notificationSound,
                      List<String> tags) {

        public Descriptor {
            if (applicationKey == null || applicationKey.isBlank()) {
                throw new IllegalArgumentException(
                        "应用描述必须带 applicationKey —— 通知与诊断都靠它认人");
            }
            if (displayName == null || displayName.isBlank()) {
                throw new IllegalArgumentException(
                        "应用必须有 displayName —— 她手机上会显示这个名字");
            }
            version = version == null || version.isBlank() ? "0.0.0" : version;
            description = description == null ? "" : description;
            notificationSound = notificationSound == null || notificationSound.isBlank()
                    ? "default" : notificationSound;
            tags = tags == null ? List.of() : List.copyOf(tags);
        }

        /**
         * 最简描述: key + 名字 + 版本, 其余取默认。
         *
         * <p>刻意<b>不</b>提供一个"从 displayName 自动推导 key"的便捷工厂:
         * displayName 是中文("她的聊天软件"), 推导出来的 key 会是空串或一串横线 ——
         * 而 key 是要写进能力 key 与通知标签的。一个"看起来更省事"的工厂
         * 在这里省掉的是唯一一处<b>必须由人做对</b>的决定。
         */
        public static Descriptor of(String applicationKey, String displayName, String version) {
            return new Descriptor(applicationKey, displayName, version, "", "default", List.of());
        }

        public Descriptor description(String text) {
            return new Descriptor(applicationKey, displayName, version, text,
                    notificationSound, tags);
        }

        public Descriptor notificationSound(String sound) {
            return new Descriptor(applicationKey, displayName, version, description,
                    sound, tags);
        }

        public Descriptor tags(String... values) {
            return new Descriptor(applicationKey, displayName, version, description,
                    notificationSound, List.of(values));
        }

        /** 一行摘要 —— 日志与诊断面板用。 */
        public String describe() {
            return displayName + " v" + version + "[" + applicationKey + "]";
        }
    }

    /**
     * 一个"只有描述没有能力"的应用 —— 给"装上了但 agent 还不会用它"的过渡态用。
     *
     * <p>它返回空能力集合而不是抛异常: 用户的原话是"让三方自己实现接口去对接",
     * 而对接是<b>分阶段</b>的 —— 今天先把应用装上(她的手机上有了这个图标),
     * 明天再把它接通。一个"没能力就装不上"的约束会逼着第三方一次写完才能验证任何东西。
     */
    interface WithoutCapabilities extends DeviceApplication {

        @Override
        default Collection<Capability> capabilities() {
            return List.of();
        }

        @Override
        default void onAttach(Device device) {
            // 默认什么都不做 —— 见本接口注释: 装上但还没接通是一个合法状态
        }

        @Override
        default void onDetach() {
            // 同上: 没有需要释放的东西
        }

        /** 大多数"还没接通"的应用只需要这两件事, 给一个更短的实现路径。 */
        @Override
        default Descriptor descriptor() {
            return Descriptor.of(id(), id(), "0.0.0")
                    .description("尚未接通的应用");
        }

        /** 平台认识的应用描述字段 —— 给文档与诊断用。 */
        static Map<String, Object> knownFields() {
            return Map.of(
                    "applicationKey", "稳定标识, 同时是通知标签",
                    "displayName", "给她看的名字",
                    "version", "自由格式版本",
                    "description", "一句话说明",
                    "notificationSound", "通知音标识, 空则用默认",
                    "tags", "自由标签");
        }
    }
}
