package com.luxera.companion.registry;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import lombok.extern.slf4j.Slf4j;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * V2.2 §1.3 P4 / §7 —— <b>把对象写进 JSONB、再原样读回来</b>。
 *
 * <h2>问题的形状</h2>
 * 本设计里所有可扩展的东西最终都进数据库的一列 JSON: 一条事件、一次活动、
 * 一个设备、一项应用配置。写进去容易 —— Jackson 会做。读出来难:
 * <pre>{@code
 * // 库里这一行:
 * {"_type":"chat.message-notified.v1","occurredAt":"...","conversationId":"c-7"}
 *
 * // 反序列化器必须回答: 该 new 哪个类?
 * //   → 平台源码里没有 "chat.message-notified" 这个词, 它是聊天平台接入时带来的
 * }</pre>
 * 答案就是那个 {@code _type} 字段 + {@link DomainTypeRegistry}。本类负责这两件事:
 * <b>写的时候打上标, 读的时候按标查表</b>。
 *
 * <h2>为什么 {@code _type} 是平铺的而不是包一层</h2>
 * 可能的另一种写法是 {@code {"_type":"...","payload":{...}}}。放弃它的原因:
 * <ul>
 *   <li>库里的 JSON 会被<b>人</b>直接看(SQL 客户端、行为分析脚本)。平铺的
 *       {@code {"_type":"...","warmth":0.42}} 一眼能读, 套一层则每次都要多点一下;</li>
 *   <li>查询要写 {@code data->>'warmth'}, 而不是 {@code data->'payload'->>'warmth'}。
 *       JSONB 的 GIN 索引对平铺字段更好用。</li>
 * </ul>
 *
 * <h2>向前兼容: 多出来的字段不报错</h2>
 * 反序列化时关掉 {@code FAIL_ON_UNKNOWN_PROPERTIES}。理由是<b>历史数据的字段只会变多</b>:
 * 一个 v2 的类读 v1 的行时, 缺的字段是 null(诚实表达"她当时没这个信息");
 * 一个 v1 的类读 v2 的行时, 多的字段被忽略(而不是让回放崩掉)。
 *
 * <p>反过来, {@code FAIL_ON_NULL_FOR_PRIMITIVES} 也关掉 —— 一个历史事件里
 * {@code magnitude} 缺失时, 我们希望它是 {@code 0.0} 而不是一条异常。这个选择有代价
 * (它会把"字段真的丢了"和"值真的是零"混起来), 所以它只在<b>读历史</b>这条路径上成立;
 * 实时路径上的事件从来不走本类 —— 它们是活对象, 不经过 JSON。
 *
 * <h2>和实体上 {@code @JsonTypeInfo} 的区别</h2>
 * Jackson 自带多态支持, 但它要求类型在<b>编译期</b>用注解列出({@code @JsonSubTypes})。
 * 那正是本设计要摆脱的东西。本类把"类型清单"外置成运行时可注册的
 * {@link DomainTypeRegistry}, 于是三方应用能在装配期把自己的类型放进来。
 */
@Slf4j
public class PolymorphicSerializer {

    /**
     * 类型标记字段名。
     *
     * <p>前导下划线的理由: 业务字段不会这么起名, 所以<b>永远不会撞</b>。
     * 用 {@code type} 会撞(很多实体自己有个叫 type 的字段), 而撞了的后果是
     * 序列化时覆盖掉真实数据 —— 一个特别难发现的数据损坏。
     */
    public static final String TYPE_FIELD = "_type";

    private final ObjectMapper mapper;
    private final DomainTypeRegistry registry;

    /** 类型名被剥离后的回退标记 —— 见 {@link #toMap}。 */
    public static final String UNTYPED_MARKER = "_untyped";

    public PolymorphicSerializer(DomainTypeRegistry registry) {
        this(registry, defaultMapper());
    }

    public PolymorphicSerializer(DomainTypeRegistry registry, ObjectMapper mapper) {
        this.registry = Objects.requireNonNull(registry, "类型注册表不能为空");
        this.mapper = Objects.requireNonNull(mapper, "ObjectMapper 不能为空");
    }

    /**
     * 本类期望的 mapper 配置。
     *
     * <p>刻意<b>不</b>直接复用 Spring 容器里那个全局 mapper 而不做任何说明:
     * 全局 mapper 常被 {@code WRITE_DATES_AS_TIMESTAMPS} 之类的开关改过,
     * 而那些开关一旦变了, 库里的时间格式就跟着变。本类用一个配置明确的实例,
     * 并接受外部传入 —— <b>想改就改, 但要显式改</b>。
     */
    public static ObjectMapper defaultMapper() {
        ObjectMapper m = new ObjectMapper();
        m.setSerializationInclusion(JsonInclude.Include.NON_NULL);
        m.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        m.disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        m.disable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES);
        // 时间统一走 ISO-8601 字符串: 库里的 JSONB 是给人看的,
        // 而 epoch 数字对人而言是不可读的。代价是稍微大一点, 但这一列不是热路径
        m.findAndRegisterModules();
        return m;
    }

    public ObjectMapper mapper() {
        return mapper;
    }

    public DomainTypeRegistry registry() {
        return registry;
    }

    // ─────────────────────────── 写 ───────────────────────────

    /**
     * 对象 → 带 {@code _type} 的 Map。
     *
     * <h3>类型没注册时怎么办</h3>
     * <b>照写, 但记一条警告并打上 {@link #UNTYPED_MARKER}</b>。
     *
     * <p>三个选项里选这个的理由:
     * <table border="1">
     *   <tr><th>选项</th><th>后果</th></tr>
     *   <tr><td>抛异常</td><td>一个三方类型忘了注册, 就让整条事件链断掉 ——
     *       而事件链断开比"这条数据读回来时是个 Map"严重得多</td></tr>
     *   <tr><td>静默照写</td><td>数据不丢, 但<b>没有任何痕迹说明它为什么读不回来</b>。
     *       排查的人会以为是序列化器坏了</td></tr>
     *   <tr><td><b>照写 + 打标 + 告警</b></td><td>数据不丢, 且那条警告直接指向
     *       "你忘了注册这个类"</td></tr>
     * </table>
     */
    public Map<String, Object> toMap(Object value) {
        Objects.requireNonNull(value, "要序列化的对象不能为空");
        Map<String, Object> data = mapper.convertValue(value,
                new TypeReference<LinkedHashMap<String, Object>>() {});

        Optional<String> typeId = registry.typeIdOf(value);
        if (typeId.isPresent()) {
            // 用 put 而不是"先移除再放": 业务字段里真有个叫 _type 的, 会被这里覆盖掉 ——
            // 而那是对的, 因为 _type 是 <b>本序列化器的保留字段</b>。
            // 与其静默地留下两个语义不同的同名字段, 不如让框架赢
            data.put(TYPE_FIELD, typeId.get());
        } else {
            data.put(UNTYPED_MARKER, value.getClass().getName());
            log.warn("[Polymorphic] {} 没有注册类型名 —— 已写入数据, 但它读回来时只会是个 Map。"
                            + "补一个 @DomainType 注解并注册即可",
                    value.getClass().getName());
        }
        return data;
    }

    public String toJson(Object value) {
        try {
            return mapper.writeValueAsString(toMap(value));
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            // 走到这里说明对象里有个 Jackson 处理不了的东西(循环引用、无法序列化的类型)。
            // 抛一个带上下文的运行时异常, 而不是让 JSON 那一列静默变成 null
            throw new SerializationFailedException(
                    "无法把 " + value.getClass().getName() + " 序列化成 JSON: " + e.getOriginalMessage(), e);
        }
    }

    public String toJsonList(List<?> values) {
        Objects.requireNonNull(values, "要序列化的列表不能为空");
        List<Map<String, Object>> maps = new ArrayList<>(values.size());
        for (Object v : values) {
            maps.add(v == null ? null : toMap(v));
        }
        try {
            return mapper.writeValueAsString(maps);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new SerializationFailedException("无法把列表序列化成 JSON: " + e.getOriginalMessage(), e);
        }
    }

    // ─────────────────────────── 读 ───────────────────────────

    /**
     * 带 {@code _type} 的 Map → 具体类型的对象。
     *
     * @throws UnknownDomainTypeException 那个类型名没有任何类声明过, 且没有默认类型可用
     */
    public Object fromMap(Map<String, Object> data) {
        return fromMap(data, null);
    }

    /**
     * 带 {@code _type} 的 Map → 具体类型的对象, 并校验它是期望的形态。
     *
     * @param expected 期望的类型或接口。为 {@code null} 表示不校验。
     *                 传它是为了把"读错了类型"这个问题<b>在边界上</b>暴露出来 ——
     *                 否则它会以 {@code ClassCastException} 的形式出现在很远的地方
     */
    public Object fromMap(Map<String, Object> data, Class<?> expected) {
        Objects.requireNonNull(data, "要反序列化的数据不能为空");

        Object rawType = data.get(TYPE_FIELD);
        if (rawType == null) {
            // 没有 _type, 但可能有 _untyped —— 说明写入时它就没注册。
            // 这时给出那个更具体的诊断, 而不是笼统的"缺 _type"
            Object untyped = data.get(UNTYPED_MARKER);
            if (untyped != null) {
                throw new UnknownDomainTypeException(String.valueOf(untyped),
                        "这条数据写入时类型 " + untyped + " 没有注册, 因此没有可读回来的类名。"
                                + "补上 @DomainType 并注册后, 历史数据仍需人工回填 _type");
            }
            throw new UnknownDomainTypeException("(缺失)",
                    "数据里没有 " + TYPE_FIELD + " 字段 —— 它可能不是本序列化器写的");
        }

        String typeId = String.valueOf(rawType);
        Class<?> target = registry.resolve(typeId)
                .or(() -> registry.resolveAnyVersion(stripVersion(typeId)))
                .orElseThrow(() -> new UnknownDomainTypeException(typeId,
                        "没有类声明过类型名 " + typeId + "。当前已注册 "
                                + registry.size() + " 个类型" + knownNamespaceHint(typeId)));

        if (expected != null && !expected.isAssignableFrom(target)) {
            throw new UnknownDomainTypeException(typeId,
                    "类型 " + typeId + " 解析到 " + target.getName()
                            + ", 但它不是期望的 " + expected.getName());
        }

        // 复制一份再剥掉标记字段 —— 直接改传入的 Map 会污染调用方的数据,
        // 而"我读了一下就把你的 Map 改了"是最难查的那类副作用
        Map<String, Object> body = new LinkedHashMap<>(data);
        body.remove(TYPE_FIELD);
        body.remove(UNTYPED_MARKER);

        return mapper.convertValue(body, target);
    }

    /** 带校验的版本, 省掉调用方的强制转换。 */
    public <T> T fromMap(Map<String, Object> data, Class<T> expected, boolean checked) {
        Object value = fromMap(data, expected);
        return expected.cast(value);
    }

    public Optional<Object> tryFromMap(Map<String, Object> data) {
        try {
            return Optional.of(fromMap(data));
        } catch (UnknownDomainTypeException e) {
            // 读历史时的正常分支: 某个三方应用被卸载了, 它的历史事件还在库里。
            // 记 WARN 并跳过, 而不是让整次回放崩掉 —— 历史的价值在于能读,
            // 哪怕读不全
            log.warn("[Polymorphic] 跳过一条读不回来的数据: {}", e.getMessage());
            return Optional.empty();
        }
    }

    public List<Object> fromJsonList(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        List<Map<String, Object>> rows;
        try {
            rows = mapper.readValue(json, new TypeReference<List<Map<String, Object>>>() {});
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new SerializationFailedException("无法把 JSON 解析成列表: " + e.getOriginalMessage(), e);
        }
        List<Object> out = new ArrayList<>(rows.size());
        for (Map<String, Object> row : rows) {
            tryFromMap(row).ifPresent(out::add);
        }
        return out;
    }

    /** 让历史数据的时间字段能被 {@link #readInstant} 读出。 */
    public Instant readInstant(Object raw) {
        if (raw == null) {
            return null;
        }
        if (raw instanceof Instant i) {
            return i;
        }
        if (raw instanceof Number n) {
            return Instant.ofEpochMilli(n.longValue());
        }
        return Instant.parse(String.valueOf(raw));
    }

    private String knownNamespaceHint(String typeId) {
        int lastDot = typeId.lastIndexOf('.');
        if (lastDot <= 0) {
            return "";
        }
        String namespace = typeId.substring(0, lastDot);
        List<String> sameNamespace = registry.under(namespace);
        if (sameNamespace.isEmpty()) {
            return "; 这个命名空间下一个类型都没注册 —— 提供它的应用可能没装上";
        }
        return "; 同一命名空间下已注册: " + sameNamespace;
    }

    private static String stripVersion(String typeId) {
        int idx = typeId.lastIndexOf(".v");
        if (idx <= 0) {
            return typeId;
        }
        String tail = typeId.substring(idx + 2);
        return tail.chars().allMatch(Character::isDigit) ? typeId.substring(0, idx) : typeId;
    }

    public String describe() {
        return "PolymorphicSerializer[" + registry.describe() + "]";
    }

    // ─────────────────────────── 失败 ───────────────────────────

    /**
     * 读回来时那个类型名没人认识。
     *
     * <p>消息里必须带上<b>已知的类型名</b> —— 一个只说"unknown type"的异常会让人
     * 去翻代码找类型清单, 而清单在运行时才确定。把当时注册表里的东西打出来,
     * 排查的人立刻就能看出"是漏注册了"还是"写进去的字符串拼错了"。
     */
    public static class UnknownDomainTypeException extends RuntimeException {
        private final String typeId;

        public UnknownDomainTypeException(String typeId, String message) {
            super(message);
            this.typeId = typeId;
        }

        public String typeId() {
            return typeId;
        }
    }

    /** 写出去的时候失败了 —— 与读不回来是两种不同的病。 */
    public static class SerializationFailedException extends RuntimeException {
        public SerializationFailedException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
