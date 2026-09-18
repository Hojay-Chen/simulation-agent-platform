package com.luxera.companion.world.object;

import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

/**
 * V2.2 §4.2 —— <b>世界对象的身份</b>: 一个带命名空间的不可变值对象, 不是裸 {@code String}。
 *
 * <h2>为什么不直接用 {@code String id}</h2>
 * 因为世界里同时活着手机、地点、环境、应用、衣柜, 而它们的 id 在类型层面长得一模一样:
 * <pre>{@code
 * // 用 String 的写法 —— 编译器无法阻止下面这两行
 * phone.setVolume(command.str("phoneId").orElse(""), level);   // 传了 placeId, 编译通过
 * place.moveTo(phoneId);                                      // 传了 phoneId, 编译通过
 * }</pre>
 * 两个 id 都是 {@code String}, 所以"用错了哪个"是一个<b>静默</b>的 bug: 它不会崩,
 * 只会让"她调了客厅的音量"变成"她调了手机的音量", 然后在行为分析里表现为一个
 * 无法解释的动作。强类型 id 把这类错误从"运行期谜题"变成"编译期红字"。
 *
 * <h2>那为什么事件里的 {@code sourceObjectId()} 还是 {@code String}</h2>
 * 这是刻意的<b>单向</b>转换, 不是设计不一致:
 * <table border="1">
 *   <tr><th></th><th>{@code ObjectId}(活引用)</th><th>{@code String}(事件载荷)</th></tr>
 *   <tr>
 *     <td>代表什么</td><td>"哪个活着的对象"</td><td>"历史上哪一条记录"</td>
 *   </tr>
 *   <tr>
 *     <td>谁用</td><td>装配代码、容器、能力实现</td><td>已落库的事件、日志、跨进程载荷</td>
 *   </tr>
 *   <tr>
 *     <td>为什么这样</td>
 *     <td>活对象需要类型安全: 写错就是 bug</td>
 *     <td>历史是<b>值</b>: 它不该被一个活对象引用钉在内存里, 也不该因为
 *         {@code ObjectId} 加了校验而读不回来(见 {@code WorldEvent.sourceObjectId()} 的说明)</td>
 *   </tr>
 * </table>
 * 所以转换只发生在"活对象把事实交给世界那一刻"({@link #value()}), 而不会反向发生 ——
 * <b>没有任何一条历史事件会把一个 {@code ObjectId} 反序列化回来</b>, 因为那意味着
 * "历史在引用现在"。
 *
 * <h2>语法约束: 为什么允许点与冒号</h2>
 * <ul>
 *   <li>点用来表达<b>归属层级</b>: {@code device.phone:phone-42} 里 {@code device.phone}
 *       是命名空间, 与 {@code EventTypeId} / {@code CapabilityDescriptor.key} 的语法同构 ——
 *       同一个世界里三套标识符长得一样, 日志扫一眼就知道谁是谁;</li>
 *   <li>冒号用来分隔命名空间与本地名, 因为点已经被命名空间内部用掉了 ——
 *       这与 {@code EventTypeId} 从右往左数两段的切分逻辑是同一个理由。</li>
 * </ul>
 * <b>不允许大写与空格</b>: id 会被写进数据库列、日志、URL 与诊断面板, 而
 * {@code Phone-42} 与 {@code phone-42} 在某个环节被当成两个对象的代价,
 * 远大于"写起来方便一点"带来的收益。
 *
 * <h2>不可变</h2>
 * 它是 {@code record}, 因此可以被安全地当作 Map 的键。这一点被
 * {@code World} 的对象表与能力注册表实打实地依赖着 —— 一个可变的 id 会让
 * "按 id 找回对象"在某次改名之后静默失配。
 */
public record ObjectId(String value) {

    /**
     * id 允许的形状。
     *
     * <p>刻意<b>宽松</b>到"只要小写、数字、点点划划都行", 而不是去定义一个严格的语法树:
     * id 的语义(它属于设备? 地点? 应用?)由<b>拥有它的对象</b>决定, 不由 id 自己决定。
     * 一个试图从 id 字符串里解析出类型的实现, 会立刻退化成一个
     * {@code switch (id.namespace())} —— 那正是 P4 要消灭的东西。
     *
     * <p>斜杠({@code /})必须在允许集里: 见 {@link #child(String)} —— 它是
     * <b>实例层级</b>的分隔符。少了它, {@code child()} 会构造出一个自己都不合法
     * 的 id, 于是每一个派生出来的子对象 id 都会在构造点抛异常 ——
     * 而那种失败看起来像"子对象不能有 id", 与真正的原因(字符集漏了一个字符)
     * 相距甚远。<b>校验规则与构造规则必须是同一套</b>, 这条纪律在这里的具体形态
     * 就是"ALLOWED 要覆盖每一个由本类自己拼出来的形状"。
     */
    private static final String ALLOWED = "[a-z0-9][a-z0-9._:/-]*";

    /** 命名空间与本地名之间的分隔符。 */
    public static final char SCOPE_SEPARATOR = ':';

    public ObjectId {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(
                    "对象 id 不能为空 —— 没有 id 的对象无法被 Action 指到, 也无法在事件里被追溯");
        }
        String trimmed = value.trim();
        if (!trimmed.equals(value)) {
            throw new IllegalArgumentException(
                    "对象 id \"" + value + "\" 首尾有空白 —— id 会被写进数据库列与日志, "
                            + "带空白的 id 会在某一次字符串比较里静默地变成另一个对象");
        }
        if (!trimmed.matches(ALLOWED)) {
            throw new IllegalArgumentException(
                    "对象 id \"" + value + "\" 不合规范: 只允许小写字母开头、由 "
                            + "小写字母、数字、点、下划线、冒号、连字符与斜杠组成(斜杠用于"
                            + "实例层级, 见 child())。大写与空格会让 Phone-42 与 phone-42 "
                            + "在某一个环节变成两个对象");
        }
    }

    // ─────────────────────────── 构造 ───────────────────────────

    public static ObjectId of(String value) {
        return new ObjectId(value);
    }

    /**
     * 带命名空间地构造: {@code of("device.phone", "phone-42")} → {@code device.phone:phone-42}。
     *
     * <p>存在的理由: 装配代码里"设备 id 的前缀"是一个真实存在的约定(与
     * {@code CapabilityDescriptor.key} 的命名空间一致), 手工拼字符串会让这个约定
     * 散落在十几处, 而其中一处漏了冒号的表现是"事件里的来源对象找不到"。
     */
    public static ObjectId of(String namespace, String localName) {
        Objects.requireNonNull(namespace, "对象 id 的命名空间不能为空");
        Objects.requireNonNull(localName, "对象 id 的本地名不能为空");
        return new ObjectId(namespace.trim() + SCOPE_SEPARATOR + localName.trim());
    }

    /**
     * 从设备 id 派生一个子对象的 id —— 例如从 {@code device.phone:phone-42} 派生出
     * 它上面那个应用的实例 id {@code device.phone:phone-42/chat}。
     *
     * <p>用 {@code /} 表示"挂在谁下面"而不是再点一层: 点是<b>类型</b>层级
     * (device.phone), 斜杠是<b>实例</b>层级(这台手机上的聊天软件)。混用两者会让
     * "这个名字是类型还是实例"变成一个见仁见智的问题。
     */
    public ObjectId child(String localName) {
        Objects.requireNonNull(localName, "子对象名不能为空");
        return new ObjectId(value + "/" + localName.trim());
    }

    // ─────────────────────────── 读取 ───────────────────────────

    /** 命名空间 —— 最后一个冒号之前的部分。没有冒号时就是整个值。 */
    public String namespace() {
        int idx = value.indexOf(SCOPE_SEPARATOR);
        return idx < 0 ? value : value.substring(0, idx);
    }

    /** 本地名 —— 最后一个冒号之后的部分。没有冒号时就是整个值。 */
    public String localName() {
        int idx = value.lastIndexOf(SCOPE_SEPARATOR);
        return idx < 0 ? value : value.substring(idx + 1);
    }

    /** 这个 id 是否定义在给定命名空间之下(含其子命名空间)。 */
    public boolean under(String candidateNamespace) {
        Objects.requireNonNull(candidateNamespace, "命名空间不能为空");
        String ns = namespace();
        return ns.equals(candidateNamespace) || ns.startsWith(candidateNamespace + ".");
    }

    /** 尽力解析 —— "垃圾进, 空出"版本, 给读取外部数据的路径用。 */
    public static Optional<ObjectId> tryParse(String text) {
        try {
            return Optional.of(new ObjectId(text));
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }

    /** 转成小写形式 —— 仅在诊断与比较时用, <b>不改动 id 本身</b>。 */
    public String lowerCase() {
        return value.toLowerCase(Locale.ROOT);
    }

    /**
     * {@code value} —— 交给 {@code WorldEvent.sourceObjectId()} 的那一刻用的就是它。
     *
     * <p>刻意让 {@code toString()} 与 {@link #value()} 相等: 一个 {@code ObjectId}
     * 出现在日志里时, 应该长得和它在数据库里的样子一模一样, 否则
     * "日志里搜到的 id"与"库里查到的 id"会需要一次人工脑内翻译。
     */
    @Override
    public String toString() {
        return value;
    }
}
