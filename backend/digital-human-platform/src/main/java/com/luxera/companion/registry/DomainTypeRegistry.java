package com.luxera.companion.registry;

import com.luxera.companion.boundary.event.EventTypeId;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * V2.2 §1.3 P4 / §5.4.6 —— <b>类型名 ↔ Java 类</b>的双向表。
 *
 * <h2>它为什么必须存在</h2>
 * 这个系统里所有"多态"的东西最终都要落进数据库的一列 JSON: 一条事件、一次活动、
 * 一个设备、一项第三方应用的配置。写进去的时候, Java 知道那是哪个类; 读出来的时候,
 * JSON 里只剩字段 —— <b>谁来告诉反序列化器该 new 哪个类?</b>
 *
 * <p>枚举答案(旧): 一个 {@code switch (type)} 分支列表。加一种就要改平台源码。
 * <br>本表答案(新): 实现类自己用 {@link DomainType} 声明名字, 启动时注册进来。
 * 平台源码里<b>永远不会出现</b>第三方的类型名。
 *
 * <h2>为什么它和 {@link CoreEventCatalog} 是两张表而不是一张</h2>
 * 因为它们回答不同的问题, 而合起来会让其中一个必然说谎:
 * <table border="1">
 *   <tr><th></th><th>{@link CoreEventCatalog}</th><th>本类</th></tr>
 *   <tr><td>回答</td><td>"平台<b>内置</b>了哪些事件, 各自什么语义"</td>
 *       <td>"运行时<b>实际存在</b>哪些类型, 怎么 new 出来"</td></tr>
 *   <tr><td>内容</td><td>平台自己的 45 条, 编译期写死</td>
 *       <td>平台 + 全部第三方, 启动期装配</td></tr>
 *   <tr><td>能写文档吗</td><td>能 —— 它是给人读的目录</td>
 *       <td>不能 —— 三方类型在平台编译时还不存在</td></tr>
 *   <tr><td>能 new 对象吗</td><td>不能 —— 它只有名字和描述</td>
 *       <td>能 —— 它握着 {@code Class<?>}</td></tr>
 * </table>
 *
 * <p>把两者强行统一会得到两种坏结果之一: 要么核心目录变成"运行时才发现有什么"的
 * 不可文档化的东西, 要么三方类型必须先在平台源码里登记 —— 那就回到枚举了。
 *
 * <h2>装配顺序决定谁赢, 这是刻意的</h2>
 * 同一个类型名被两个不同的类声明时: 记 WARN, <b>保留先注册的</b>。
 * 理由与 {@link CapabilityRegistry} 完全相同 —— 装配顺序可控(自己的先注册),
 * 而 classpath 扫描顺序不可控。<b>让重名冲突的结果取决于 jar 的加载次序,
 * 是一个在不同机器上表现为不同行为的故障</b>, 而那种故障几乎无法从日志里查出来。
 */
@Slf4j
public class DomainTypeRegistry {

    /** 完整类型名(含版本, 形如 {@code chat.message-notified.v1}) → 实现类。 */
    private final Map<String, Class<?>> byId = new LinkedHashMap<>();

    /** 反向索引: 类 → 类型名。序列化时用它给对象打标。 */
    private final Map<Class<?>, String> byClass = new LinkedHashMap<>();

    /** 注册顺序 —— 诊断面板回答"谁先来的"。 */
    private final List<String> registrationOrder = new ArrayList<>();

    /** 曾经被忽略掉的冲突, 留给诊断面板 —— 静默地丢掉一个类型是很难查的。 */
    private final List<String> conflicts = new ArrayList<>();

    private static final int CONFLICT_LOG_LIMIT = 64;

    // ─────────────────────────── 注册 ───────────────────────────

    /**
     * 从类上的 {@link DomainType} 注解注册。
     *
     * @return 这次注册是否生效
     * @throws IllegalArgumentException 这个类没标 {@link DomainType} ——
     *         那说明调用方的类扫描范围写错了(扫到了不该扫的包), 而静默跳过会让
     *         "少注册了一个类型"在很久以后表现为"某条历史事件读不出来"
     */
    public boolean register(Class<?> type) {
        Objects.requireNonNull(type, "要注册的类型不能为空");
        DomainType annotation = type.getAnnotation(DomainType.class);
        if (annotation == null) {
            throw new IllegalArgumentException(
                    type.getName() + " 上没有 @DomainType 注解 —— 它无法被序列化成可读回来的数据。"
                            + "如果它本来就不需要被持久化, 请不要把它交给本注册表");
        }
        return register(EventTypeId.of(namespaceOf(annotation.value()), nameOf(annotation.value())),
                annotation.version(), type);
    }

    /**
     * 显式指定名字注册 —— 给<b>无法改源码</b>的三方类用。
     *
     * <p>为什么这个重载是必要的: 你要接入的那个 SDK 类在别人的 jar 里, 你没法给它加注解。
     * 没有这个入口, 唯一的选择就是写一个包装类 —— 而包装类会让
     * "注册的类型"和"实际 new 出来的类型"变成两个, 之后每一次 {@code instanceof} 都要想一下
     * 该用哪个。
     *
     * @param type 实现类。必须有一个<b>公开无参构造</b>, 否则反序列化时才失败 ——
     *             那时候的堆栈离注册处很远。本方法提前检查并直接报出来
     */
    public boolean register(String typeId, Class<?> type) {
        Objects.requireNonNull(typeId, "类型名不能为空");
        EventTypeId parsed = EventTypeId.tryParse(typeId).orElseThrow(() ->
                new IllegalArgumentException(
                        "类型名 " + typeId + " 不合规范。要求形如 namespace.name.vN, "
                                + "例如 chat.message-notified.v1"));
        return register(parsed, parsed.majorVersion(), type);
    }

    private boolean register(EventTypeId id, int version, Class<?> type) {
        String typeId = withVersion(id, version);

        Class<?> existing = byId.get(typeId);
        if (existing != null) {
            if (existing == type) {
                log.debug("[DomainTypeRegistry] {} 已注册过同一个类, 跳过", typeId);
                return false;
            }
            String note = typeId + " 已由 " + existing.getName() + " 占用, 忽略 " + type.getName();
            log.warn("[DomainTypeRegistry] 类型名冲突: {} —— 保留先注册的是刻意的: "
                    + "装配顺序可控, 而 classpath 扫描顺序不可控", note);
            rememberConflict(note);
            return false;
        }

        String claimedByClass = byClass.get(type);
        if (claimedByClass != null && !claimedByClass.equals(typeId)) {
            // 同一个类声明两个名字: 不阻止(可能是别名), 但要说出来。
            // 真正的危险是有人以为改名了, 而老名字还在被历史数据引用
            log.info("[DomainTypeRegistry] {} 同时以 {} 和 {} 两个名字注册 —— 两者都能读回来",
                    type.getSimpleName(), claimedByClass, typeId);
        }

        byId.put(typeId, type);
        byClass.put(type, typeId);
        registrationOrder.add(typeId);
        log.debug("[DomainTypeRegistry] 注册 {} → {}", typeId, type.getName());
        return true;
    }

    public void registerAll(Iterable<? extends Class<?>> types) {
        types.forEach(this::register);
    }

    // ─────────────────────────── 解析 ───────────────────────────

    /**
     * 类型名 → 实现类。
     *
     * <p>返回 {@link Optional} 而不是抛异常: "读不出来"在<b>读历史数据</b>时是一个
     * 正常处境(某个第三方应用被卸载了, 它的历史事件还在库里), 而不是故障。
     * 调用方该做的是记一条"这个类型现在没人认识了"并跳过, 不是让整个回放崩掉。
     */
    public Optional<Class<?>> resolve(String typeId) {
        return typeId == null ? Optional.empty() : Optional.ofNullable(byId.get(typeId));
    }

    /**
     * 不带版本地解析 —— 用于"我知道它是哪一族, 具体哪个版本都行"的场景。
     *
     * <p>典型用法: 诊断面板要列出"历史上出现过的全部 environment.* 事件"。
     * 它不关心是 v1 还是 v2, 但库里两种都有。
     */
    public Optional<Class<?>> resolveAnyVersion(String subscriptionKey) {
        Objects.requireNonNull(subscriptionKey, "订阅键不能为空");
        // 精确命中优先(同族只有一个版本时的常见情形)
        Optional<Class<?>> exact = resolve(subscriptionKey);
        if (exact.isPresent()) {
            return exact;
        }
        return byId.entrySet().stream()
                .filter(e -> e.getKey().startsWith(subscriptionKey + ".v"))
                .map(Map.Entry::getValue)
                .findFirst();
    }

    public Optional<Class<?>> resolve(EventTypeId id) {
        return id == null ? Optional.empty() : resolve(id.toString());
    }

    /**
     * 对象 → 类型名。序列化时用它决定往 JSON 里写哪个 {@code _type}。
     *
     * <p>找不到时返回 {@link Optional#empty()} 而不是抛异常, 因为调用方
     * (序列化器)才是有上下文知道该怎么办的那一方 —— 它可以选择<b>记一条警告并
     * 按普通 POJO 写进去</b>(丢掉多态但数据不丢), 或者拒绝写入。
     */
    public Optional<String> typeIdOf(Object value) {
        if (value == null) {
            return Optional.empty();
        }
        Class<?> type = value.getClass();
        String direct = byClass.get(type);
        if (direct != null) {
            return Optional.of(direct);
        }
        // 子类没注册、父类注册了 —— 按父类写。
        // 这个回退是刻意的: 第三方经常写自己的子类而不注册。写父类名至少能读回来,
        // 而"读不回来"会让这条记录永久损坏
        return byClass.entrySet().stream()
                .filter(e -> e.getKey().isAssignableFrom(type))
                .min((a, b) -> Integer.compare(depth(a.getKey(), type), depth(b.getKey(), type)))
                .map(Map.Entry::getValue);
    }

    /** 从 {@code type} 往上走到 {@code ancestor} 要走几步 —— 用来选最近的祖先。 */
    private static int depth(Class<?> ancestor, Class<?> type) {
        int steps = 0;
        for (Class<?> c = type; c != null && c != ancestor; c = c.getSuperclass()) {
            steps++;
        }
        return steps;
    }

    // ─────────────────────────── 查询 ───────────────────────────

    /** 全部已注册的类型名, 按注册顺序。 */
    public Set<String> typeIds() {
        return new LinkedHashSet<>(registrationOrder);
    }

    public int size() {
        return byId.size();
    }

    public boolean isEmpty() {
        return byId.isEmpty();
    }

    /** 某个命名空间下的全部类型 —— {@code under("chat")} 拿到聊天平台贡献的全部类型。 */
    public List<String> under(String namespace) {
        Objects.requireNonNull(namespace, "命名空间不能为空");
        String prefix = namespace.endsWith(".") ? namespace : namespace + ".";
        return byId.keySet().stream().filter(k -> k.startsWith(prefix)).toList();
    }

    /** 全部命名空间 —— 回答"这个 agent 的世界里有哪些来源"。 */
    public Set<String> namespaces() {
        Set<String> out = new LinkedHashSet<>();
        byId.keySet().forEach(k -> {
            EventTypeId id = EventTypeId.tryParse(k).orElse(null);
            if (id != null) {
                out.add(id.namespace());
            }
        });
        return out;
    }

    /**
     * 候选类里<b>没有</b>被注册的那些。
     *
     * <p>存在的意义: 类扫描之后用它做一次自检。三方接入时最常见的错误是
     * "我带了一堆类但忘了注册" —— 而症状是"这个类型读不回来", 直到某次重启回放
     * 历史数据才暴露。<b>让它在启动时就以一个明确的清单出现</b>, 比让它变成一个
     * 运行期谜题好得多。
     */
    public List<Class<?>> unregistered(Collection<Class<?>> candidates) {
        List<Class<?>> out = new ArrayList<>();
        for (Class<?> c : candidates) {
            if (!byClass.containsKey(c)) {
                out.add(c);
            }
        }
        return List.copyOf(out);
    }

    /** 被忽略掉的类型名冲突。诊断面板用。 */
    public List<String> conflicts() {
        return List.copyOf(conflicts);
    }

    private void rememberConflict(String note) {
        if (conflicts.size() >= CONFLICT_LOG_LIMIT) {
            conflicts.remove(0);
        }
        conflicts.add(note);
    }

    /** 只给测试用。 */
    public void clear() {
        byId.clear();
        byClass.clear();
        registrationOrder.clear();
        conflicts.clear();
    }

    public String describe() {
        return "DomainTypeRegistry(" + byId.size() + " 个类型, " + namespaces().size()
                + " 个命名空间" + (conflicts.isEmpty() ? "" : ", " + conflicts.size() + " 处冲突") + ")";
    }

    // ─────────────────────────── 名字处理 ───────────────────────────

    /**
     * 注解值和 {@link EventTypeId} 之间的转换。
     *
     * <p>注解值只写 {@code namespace.name}(版本另给一个字段), 因为让三方在一个字符串里
     * 同时写名字和版本, 几乎必然有人写成 {@code chat.message-notified-v1}(短横线连接)
     * 或 {@code chat.message-notified.1}(少个 v)。<b>把版本拆成独立字段,
     * 就不存在"格式对不对"这个问题</b>。
     */
    private static String namespaceOf(String value) {
        EventTypeId id = parseAnnotationValue(value);
        return id.namespace();
    }

    private static String nameOf(String value) {
        EventTypeId id = parseAnnotationValue(value);
        return id.name();
    }

    private static EventTypeId parseAnnotationValue(String value) {
        String text = value == null ? "" : value.trim();
        if (text.isEmpty()) {
            throw new IllegalArgumentException("@DomainType 的值不能为空");
        }
        // 允许三方自带 ".vN" 后缀(有人一定会这么写), 那是宽容而不是纵容:
        // 版本以注解的 version() 为准, 这里只把后缀剥掉
        EventTypeId id = EventTypeId.tryParse(text).orElse(null);
        if (id != null) {
            return id;
        }
        int lastDot = text.lastIndexOf('.');
        if (lastDot <= 0 || lastDot == text.length() - 1) {
            throw new IllegalArgumentException(
                    "@DomainType(\"" + text + "\") 不合规范: 要求形如 namespace.name, "
                            + "例如 chat.message-notified");
        }
        return EventTypeId.of(text.substring(0, lastDot), text.substring(lastDot + 1));
    }

    private static String withVersion(EventTypeId id, int version) {
        if (version == id.majorVersion()) {
            return id.toString();
        }
        return id.subscriptionKey() + ".v" + version;
    }
}
