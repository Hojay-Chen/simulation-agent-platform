package com.luxera.companion.boundary.action;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * V2.2 §5.7 —— <b>能力的自描述</b>。它是 Skill / Tool / MCP tool 三种说法的同一份东西。
 *
 * <h2>为什么这三种说法必须统一</h2>
 * 用户要求 world 里的对象方法"可以以 skill、工具、MCP 等形式供 agent 使用"。
 * 若为三种形式各写一份描述, 会立刻得到三个会各自漂移的真相:
 * <pre>
 *   Skill 描述改了 → 工具清单没改 → LLM 按旧描述调用 → 参数不对
 * </pre>
 * 所以只有<b>一份</b> {@code CapabilityDescriptor}, 三个消费方各自把它翻译成自己的形状:
 * <table border="1">
 *   <tr><th>消费方</th><th>怎么用这份描述</th></tr>
 *   <tr><td>LLM(工具调用)</td><td>{@link #parameterSchema()} → JSON Schema 的工具定义</td></tr>
 *   <tr><td>MCP 客户端</td><td>同上, 外面套一层 MCP 的 {@code tools/list} 响应</td></tr>
 *   <tr><td>前端(能力面板)</td><td>{@link #title()} / {@link #description()} / schema 生成的表单</td></tr>
 *   <tr><td>决策层(确定性引擎)</td><td>只读 {@link #key()} 与 {@link #sideEffect()}</td></tr>
 * </table>
 *
 * <h2>{@link #sideEffect()} 为什么重要到要单独一个字段</h2>
 * 因为它是<b>可逆性</b>的声明, 而可逆性决定了三件事: 要不要问她、能不能重试、
 * 失败后要不要补偿。把"看一眼手机"(NONE)和"删掉一条消息"(IRREVERSIBLE)
 * 用同一个 {@code dangerous=false} 布尔表达, 会让这两种截然不同的操作获得
 * 同样的处置 —— 而其中一个的代价是不可撤销的。
 *
 * <h2>参数模式用 Map 而不是 JSON Schema 库的类型</h2>
 * 因为 {@code boundary/} 不该绑定任何一个 schema 库 —— 换库会变成一个跨模块的改动。
 * 这里的 Map 就是 JSON Schema 的字面结构({@code {"type":"object","properties":{...}}})。
 * 让需要强类型的消费方自己去反序列化, 而不是让边界层替所有人选一个库。
 */
public record CapabilityDescriptor(
        String key,
        String title,
        String description,
        Map<String, Object> parameterSchema,
        Map<String, Object> returnSchema,
        SideEffect sideEffect,
        boolean requiresConfirmation,
        Set<String> tags) {

    public CapabilityDescriptor {
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException(
                    "能力必须有 key —— 它是决策层与 LLM 用来指名道姓的东西");
        }
        // key 的形状与事件类型一致(namespace.name), 复用同一套语法校验的理由也一样:
        // 宽松的语法会让 chat.sendMessage / chat.send_message 同时存在, 而失配是静默的
        if (!key.matches("[a-z][a-z0-9-]*(\\.[a-z][a-z0-9-]*)+")) {
            throw new IllegalArgumentException(
                    "能力 key 必须是 namespace.name 形状(小写、点分), 收到 \"" + key + "\"");
        }
        title = title == null || title.isBlank() ? key : title;
        description = description == null ? "" : description;
        parameterSchema = parameterSchema == null ? Map.of("type", "object", "properties", Map.of())
                : Map.copyOf(parameterSchema);
        returnSchema = returnSchema == null ? Map.of() : Map.copyOf(returnSchema);
        tags = tags == null ? Set.of() : Set.copyOf(tags);
    }

    /** 能力的命名空间 —— key 里最后一个点之前的部分。 */
    public String namespace() {
        return key.substring(0, key.lastIndexOf('.'));
    }

    /** 能力的短名 —— key 里最后一个点之后的部分。 */
    public String name() {
        return key.substring(key.lastIndexOf('.') + 1);
    }

    /**
     * 这个能力是不是<b>只读</b>的。
     *
     * <p>决策层用它回答"我可以随便多做几次吗"。只读能力可以自由重试,
     * 而有副作用的能力重试必须带幂等键。
     */
    public boolean readOnly() {
        return sideEffect == SideEffect.NONE;
    }

    /**
     * 这个能力是不是<b>不可撤销</b>的。
     *
     * <p>不可撤销 + {@link #requiresConfirmation()} = 她必须先想清楚。
     */
    public boolean irreversible() {
        return sideEffect == SideEffect.IRREVERSIBLE;
    }

    /**
     * 副作用的性质。
     *
     * <p>{@link #NONE} 与 {@link #READ} 的区别是"世界有没有留下痕迹":
     * 看一眼手机不改变任何东西(READ), 而"手机屏幕亮了"改变了屏幕状态(NONE 还是
     * READ 取决于屏幕是不是世界状态的一部分 —— 在这个仿真里它是, 所以
     * {@code phone.read-messages} 是 READ, 而 {@code phone.peek} 是 NONE)。
     *
     * <p>这个区分刻意保留得比"有用"更细一点: 一个把两者混为一谈的系统无法回答
     * "她刚才那些动作里, 哪些真的改变了世界"。而那个问题在做行为分析时一定会被问到。
     */
    public enum SideEffect {
        /** 什么都没改变。 */
        NONE,
        /** 读了什么。世界的状态没有变, 但"她读过"这件事被记下了。 */
        READ,
        /** 改变了世界, 而且<b>可以改回来</b>。 */
        WRITE,
        /** 改变了世界, <b>改不回来</b>。发出去的消息、删掉的东西。 */
        IRREVERSIBLE
    }

    // ─────────────────────────── 构造 ───────────────────────────

    public static Builder of(String key) {
        return new Builder(key);
    }

    /**
     * 流式构造器。
     *
     * <p>存在的理由: 一个能力描述有八个字段, 而其中大部分在多数能力上都是默认值。
     * 用 record 的规范构造器会让每个能力注册都变成八行 —— 于是"加一个能力"
     * 这件事在处理起来显得比实际更麻烦, 而那种感觉会真实地阻止人去加能力。
     */
    public static final class Builder {
        private final String key;
        private String title;
        private String description = "";
        private Map<String, Object> parameterSchema;
        private Map<String, Object> returnSchema;
        private SideEffect sideEffect = SideEffect.WRITE;
        private boolean requiresConfirmation = false;
        private Set<String> tags = Set.of();

        private Builder(String key) {
            this.key = key;
        }

        public Builder title(String v) {
            this.title = v;
            return this;
        }

        public Builder description(String v) {
            this.description = v;
            return this;
        }

        /** 参数模式 —— 结构就是 JSON Schema 的 {@code properties} 部分。 */
        public Builder parameters(Map<String, Object> v) {
            this.parameterSchema = Map.of("type", "object", "properties", v);
            return this;
        }

        public Builder parameters(Map<String, Object> properties, List<String> required) {
            this.parameterSchema = Map.of(
                    "type", "object",
                    "properties", properties,
                    "required", required);
            return this;
        }

        public Builder returns(Map<String, Object> v) {
            this.returnSchema = v;
            return this;
        }

        public Builder sideEffect(SideEffect v) {
            this.sideEffect = v;
            return this;
        }

        public Builder requiresConfirmation(boolean v) {
            this.requiresConfirmation = v;
            return this;
        }

        public Builder tags(String... v) {
            this.tags = Set.of(v);
            return this;
        }

        public CapabilityDescriptor build() {
            return new CapabilityDescriptor(key, title, description, parameterSchema, returnSchema,
                    sideEffect, requiresConfirmation, tags);
        }
    }
}
