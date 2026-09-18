package com.luxera.companion.registry;

import com.luxera.companion.boundary.action.Capability;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * V2.2 §5.8 —— <b>世界上存在哪些能力</b>。
 *
 * <h2>"存在"与"可用"是两件事</h2>
 * 用户要求第三方软件"自己来实现这个'手机应用'接口的实现类"。于是注册表里会出现
 * 宿主编译时还不存在的能力。这就带来一个必须分清的区别:
 *
 * <table border="1">
 *   <tr><th>问题</th><th>谁回答</th><th>例子</th></tr>
 *   <tr>
 *     <td>"世界上有没有这个能力"</td>
 *     <td>{@link #find(String)}</td>
 *     <td>手机里有聊天应用 → 有 {@code chat.send-message}</td>
 *   </tr>
 *   <tr>
 *     <td>"她此刻能不能用它"</td>
 *     <td>{@code ActionFabric.availableCapabilities(now)}</td>
 *     <td>手机没电 → 能力存在, 但此刻不可用</td>
 *   </tr>
 * </table>
 *
 * <p>把这两件事混起来(比如"没电就把能力从注册表里摘掉")会造成一个很难查的故障:
 * 她"忘了"自己会发消息, 而不是"试着发但发不出去"。前者是失忆, 后者是生活。
 *
 * <h2>注册是<b>幂等</b>的, 冲突是<b>显式</b>的</h2>
 * 同一个 key 注册两次:
 * <ul>
 *   <li>同一个实例 → 静默跳过(装配代码重复注册是常态);</li>
 *   <li><b>不同实例</b> → 记 WARN 并<b>保留先注册的那个</b>。</li>
 * </ul>
 *
 * <p>为什么冲突不抛异常: 两个第三方应用各注册了一个 {@code chat.send-message}
 * 是<b>会真实发生</b>的事(一个是我们自己的聊天软件, 一个是某个兼容客户端)。
 * 抛异常会让整个 agent 起不来, 而那比"有一个应用的能力没生效"严重得多 ——
 * 后者至少还能跑, 且日志里明明白白写着谁赢了。
 *
 * <p>为什么保留先注册的: 那是装配顺序决定的, 而装配顺序是<b>可控的</b>(自己的应用先注册)。
 * 保留后注册的会让"谁赢"取决于扫描 classpath 的顺序 —— 一个在不同机器上可能不同的东西。
 */
@Slf4j
public class CapabilityRegistry {

    private final Map<String, Capability> byKey = new LinkedHashMap<>();

    /** 注册顺序 —— 诊断时用它回答"谁先来的"。 */
    private final List<String> registrationOrder = new CopyOnWriteArrayList<>();

    /**
     * 注册一项能力。
     *
     * @return 这次注册是否生效(冲突时为 {@code false})
     */
    public boolean register(Capability capability) {
        Objects.requireNonNull(capability, "要注册的能力不能为空");
        String key = capability.key();
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException(
                    "能力 " + capability.getClass().getName() + " 的 descriptor().key() 为空 —— "
                            + "没有 key 的能力无法被决策层指名调用, 等于没注册");
        }

        Capability existing = byKey.get(key);
        if (existing != null) {
            if (existing == capability) {
                log.debug("[CapabilityRegistry] {} 已注册过同一实例, 跳过", key);
                return false;
            }
            log.warn("[CapabilityRegistry] 能力 key 冲突: {} —— 已由 {} 注册, 忽略后来的 {}。"
                            + "保留先注册的是刻意的: 装配顺序可控, 而 classpath 扫描顺序不可控",
                    key, existing.getClass().getName(), capability.getClass().getName());
            return false;
        }

        byKey.put(key, capability);
        registrationOrder.add(key);
        log.debug("[CapabilityRegistry] 注册 {} ({})", key, capability.descriptor().sideEffect());
        return true;
    }

    public void registerAll(Iterable<? extends Capability> capabilities) {
        capabilities.forEach(this::register);
    }

    public Optional<Capability> find(String key) {
        return key == null ? Optional.empty() : Optional.ofNullable(byKey.get(key));
    }

    /**
     * 按命名空间前缀查能力。
     *
     * <p>给"某个应用提供的能力"这个场景用: {@code under("chat.")} 拿到聊天应用的全部能力,
     * 于是"卸载聊天应用"就可以表达成"注销它注册过的那些 key", 而不需要宿主认识它。
     */
    public List<Capability> under(String namespacePrefix) {
        Objects.requireNonNull(namespacePrefix, "命名空间前缀不能为空");
        String prefix = namespacePrefix.endsWith(".") ? namespacePrefix : namespacePrefix + ".";
        List<Capability> out = new ArrayList<>();
        byKey.forEach((k, v) -> {
            if (k.startsWith(prefix)) {
                out.add(v);
            }
        });
        return List.copyOf(out);
    }

    /** 注销一批能力 —— 第三方应用卸载时用。 */
    public int unregisterAll(Collection<String> keys) {
        int removed = 0;
        for (String key : keys) {
            if (byKey.remove(key) != null) {
                registrationOrder.remove(key);
                removed++;
            }
        }
        if (removed > 0) {
            log.info("[CapabilityRegistry] 注销了 {} 项能力", removed);
        }
        return removed;
    }

    /** 全部能力, 按注册顺序。 */
    public List<Capability> all() {
        return registrationOrder.stream().map(byKey::get).filter(Objects::nonNull).toList();
    }

    /** 全部能力的 key。给诊断面板与 LLM 工具清单用。 */
    public Set<String> keys() {
        return Set.copyOf(byKey.keySet());
    }

    public int size() {
        return byKey.size();
    }

    public boolean isEmpty() {
        return byKey.isEmpty();
    }

    /** 全部命名空间 —— 回答"这个 agent 装了哪些应用"。 */
    public Set<String> namespaces() {
        java.util.Set<String> out = new java.util.LinkedHashSet<>();
        byKey.keySet().forEach(k -> {
            int dot = k.lastIndexOf('.');
            if (dot > 0) {
                out.add(k.substring(0, dot));
            }
        });
        return out;
    }

    /** 清空 —— 只给测试用。 */
    public void clear() {
        byKey.clear();
        registrationOrder.clear();
    }

    public String describe() {
        return "CapabilityRegistry(" + byKey.size() + " 项, " + namespaces().size() + " 个命名空间)";
    }
}
