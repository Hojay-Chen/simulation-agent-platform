package com.luxera.companion.human.mind.intention;

import com.luxera.companion.registry.DomainTypeRegistry;
import com.luxera.companion.registry.DomainType;

import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

/**
 * V2.2 §3.4.7 / §1.3 P4 —— <b>意图类型的<b>运行时</b>注册表</b>。
 *
 * <h2>它解决的具体问题</h2>
 * {@link Intention} 是开放对象, 于是"她想做哪几件事"这个清单在<b>编译期不存在</b>。
 * 但有两件事必须在运行时回答:
 *
 * <ol>
 *   <li><b>"这条历史记录说的是哪种意图?"</b> —— 从库里读回来的 JSON 里有一个类型名,
 *       要把它变回一个类。没有这一步, 三个月前的意图全部读不出来;</li>
 *   <li><b>"有哪些种意图?"</b> —— 诊断面板、LLM 的候选清单、以及"她这周想做哪几类事"
 *       的统计都要它。这正是用户对 V2.1 的那句批评("不了解的人看完完全不知道都有哪些 event")
 *       在意图上的翻版。</li>
 * </ol>
 *
 * <h2>为什么它不重新发明一套, 而是包着 {@link DomainTypeRegistry}</h2>
 * 因为事件类型已经解决了同一个问题, 而且它的解法里有几条是踩过坑才有的:
 * 重名冲突<b>保留先注册的</b>(装配顺序可控, classpath 扫描顺序不可控)、
 * 同一个类可以有两个名字(改名后老数据仍然读得回来)、
 * 解析失败返回 {@link Optional} 而不是抛异常("读不出来"在读历史数据时是正常处境)。
 * 再写一套会把这些坑重踩一遍, 而"意图"与"事件"在这几点上没有任何区别。
 *
 * <p>本类<b>只加</b>一件事: <b>从类型名造出一个实例</b>。事件那边不需要这个能力
 * (事件是别人 new 出来交给总线的), 而意图需要 —— 因为"她想做某件事"这句话
 * 有时候是从数据里来的。
 *
 * <h2>它刻意不做什么</h2>
 * <ul>
 *   <li><b>不做全局单例</b>。每个 {@code Mind} 可以有自己的一个 —— 一个只认识
 *       "读书"这一个意图的 agent 与一个认识三十个的 agent, 是两份不同的知识。
 *       做成静态全局会逼所有人共享同一份注册表, 而"谁的意图被谁注册了"从此无法回答;</li>
 *   <li><b>不做扫描</b>。没有 {@code scanPackage("...")}。classpath 扫描顺序不可控,
 *       而一个顺序不可控的注册表会让"哪条意图被认识"取决于打包方式 ——
 *       而是装配代码显式调用 {@link #register(Class)}。</li>
 * </ul>
 */
@Slf4j
public final class IntentionRegistry {

    private final DomainTypeRegistry types = new DomainTypeRegistry();
    private final Map<String, Supplier<? extends Intention>> factories = new LinkedHashMap<>();

    public IntentionRegistry() {
    }

    /**
     * 从类上的 {@link DomainType} 注解注册。
     *
     * <p>这是<b>第三方扩展的正常路径</b>: 写一个实现 {@link Intention} 的类,
     * 标上 {@code @DomainType("lab.finish-experiment")}, 在这里注册一行。
     * 平台源码一行都不用改 —— 这就是 §1.3 P4 要的效果。
     */
    public IntentionRegistry register(Class<? extends Intention> type) {
        Objects.requireNonNull(type, "要注册的意图类型不能为空");
        types.register(type);
        return this;
    }

    /**
     * 注册一个类型名, 并给它一个"怎么造出来"的工厂。
     *
     * <p>两个参数分工明确: {@code typeId} 是<b>数据里的名字</b>(它会进 JSON 与数据库),
     * 工厂是<b>怎么 new 出来</b>。只给名字的话, 反序列化框架需要无参构造 ——
     * 而一个需要参数(她的名字、她要找的那个人)的意图没有无参构造, 于是它读不回来。
     * 工厂把这个问题挡在注册处, 而不是等反序列化时才炸。
     *
     * @param typeId 形如 {@code namespace.name.vN}, 与事件类型同一套命名规范
     */
    public IntentionRegistry register(String typeId, Class<? extends Intention> type,
                                      Supplier<? extends Intention> factory) {
        Objects.requireNonNull(typeId, "类型名不能为空");
        Objects.requireNonNull(type, "要注册的意图类型不能为空");
        types.register(typeId, type);
        if (factory != null) {
            factories.put(typeId, factory);
        }
        return this;
    }

    /**
     * 注册一个类, 并指定它的工厂 —— 类型名取自 {@link DomainType} 注解。
     *
     * <p>它与 {@link #register(String, Class, Supplier)} 的差别只在名字从哪来。
     * 名字由注解给的那一份更好维护(改注解就改了两处), 但它要求<b>你能改那个类的源码</b> ——
     * 别人的 jar 里的类只能走显式给名字的那条路。
     */
    public IntentionRegistry register(Class<? extends Intention> type,
                                      Supplier<? extends Intention> factory) {
        Objects.requireNonNull(type, "要注册的意图类型不能为空");
        types.register(type);
        DomainType annotation = type.getAnnotation(DomainType.class);
        if (annotation != null && factory != null) {
            factories.put(annotation.value() + ".v" + annotation.version(), factory);
        }
        return this;
    }

    // ─────────────────────────── 解析 ───────────────────────────

    /** 类型名 → 实现类。"读不出来"返回空, 而不是抛异常 —— 见 {@link DomainTypeRegistry#resolve}。 */
    public Optional<Class<? extends Intention>> resolve(String typeId) {
        return types.resolve(typeId).flatMap(this::asIntention);
    }

    /**
     * 类型名 → 一个实例。<b>本类相对事件注册表多出来的那一件事。</b>
     *
     * <p>没有工厂的类型返回空 —— 这说明它只被注册了"名字与类", 没有被注册
     * "怎么造出来"。这不是错误(它仍然能被反序列化框架接住), 但调用方要能区分
     * "不认识这种意图"与"认识但造不出来"。
     */
    public Optional<Intention> create(String typeId) {
        if (typeId == null) {
            return Optional.empty();
        }
        Supplier<? extends Intention> factory = factories.get(typeId);
        if (factory != null) {
            return Optional.ofNullable(factory.get());
        }
        return resolve(typeId).flatMap(this::instantiate);
    }

    /** 一个意图是哪种类型 —— 它被抓去做持久化、进日志、被统计时用。 */
    public Optional<String> typeIdOf(Intention intention) {
        return intention == null ? Optional.empty() : types.typeIdOf(intention);
    }

    /** 这一族下注册了哪些类型 —— 前缀匹配, 例如 {@code "life."}。 */
    public List<String> under(String namespace) {
        return types.under(namespace);
    }

    public Set<String> typeIds() {
        return types.typeIds();
    }

    public int size() {
        return types.size();
    }

    public boolean isEmpty() {
        return types.isEmpty();
    }

    /** 注册过名字但没有工厂的类型 —— 诊断用, 见 {@link #create}。 */
    public List<String> withoutFactory() {
        List<String> out = new ArrayList<>();
        for (String id : types.typeIds()) {
            if (!factories.containsKey(id)) {
                out.add(id);
            }
        }
        return List.copyOf(out);
    }

    public void clear() {
        types.clear();
        factories.clear();
    }

    public String describe() {
        return "IntentionRegistry[认识 " + types.size() + " 种意图, 其中 "
                + factories.size() + " 种能直接造出来]";
    }

    @Override
    public String toString() {
        return describe();
    }

    // ─────────────────────────── 内部 ───────────────────────────

    @SuppressWarnings("unchecked")
    private Optional<Class<? extends Intention>> asIntention(Class<?> type) {
        if (!Intention.class.isAssignableFrom(type)) {
            log.warn("[IntentionRegistry] {} 不是 Intention —— 这个类型名注册错了地方", type.getName());
            return Optional.empty();
        }
        return Optional.of((Class<? extends Intention>) type);
    }

    private Optional<Intention> instantiate(Class<? extends Intention> type) {
        try {
            var constructor = type.getDeclaredConstructor();
            constructor.setAccessible(true);
            return Optional.of(constructor.newInstance());
        } catch (ReflectiveOperationException e) {
            log.debug("[IntentionRegistry] {} 没有无参构造, 也没注册工厂 —— 它只能被反序列化接住",
                    type.getName());
            return Optional.empty();
        }
    }
}
