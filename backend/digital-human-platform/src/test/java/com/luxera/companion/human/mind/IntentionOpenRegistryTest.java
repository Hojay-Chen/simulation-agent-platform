package com.luxera.companion.human.mind;

import com.luxera.companion.human.mind.intention.Feasibility;
import com.luxera.companion.human.mind.intention.Intention;
import com.luxera.companion.human.mind.intention.IntentionContext;
import com.luxera.companion.human.mind.intention.IntentionId;
import com.luxera.companion.human.mind.intention.IntentionPriority;
import com.luxera.companion.human.mind.intention.IntentionRegistry;
import com.luxera.companion.registry.DomainType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * V2.2 §3.4.7 —— <b>Intention 是开放对象</b>, 不是枚举。
 *
 * <blockquote>
 * LLM 可以产生「我想去实验室把今天的实验做完」→ 转换成 {@code ResearchExperimentIntent}
 * → 再形成 {@code PlanItem}。
 * </blockquote>
 *
 * <h2>这个测试在替谁说话</h2>
 * 替<b>第三方</b>。本测试文件就是"另一个团队带着一个新意图类型来接入"的最小剧本:
 * 它在<b>测试源码里</b>定义了一个 {@link FinishExperiment},
 * 用运行时注册把它挂上, 然后整条链路(注册 → 解析 → 构造 → 可行性判断)跑通。
 *
 * <p>它证明的是: 加一种"她想做的事"<b>不需要改平台源码</b>。若哪天有人把
 * {@code Intention} 换成枚举, 这个测试会编译失败 —— 而编译失败在这里是好事:
 * 它比"运行时发现某个意图类型不认识了"早得多。
 *
 * <h2>为什么用 {@code @DomainType} 而不是写死一个字符串</h2>
 * 因为 {@code @DomainType} 是这套代码库里"开放对象"的既有写法(见
 * {@code registry/DomainTypeRegistry})。换一套写法就等于告诉后面的人
 * "意图的注册方式与别处不一样", 而那种不一致会让人以为这里有特殊理由 —— 其实没有。
 */
class IntentionOpenRegistryTest {

    private static final Instant T = Instant.parse("2026-03-02T14:00:00Z");

    /**
     * 一个第三方意图类型 —— 它只出现在测试源码里。
     *
     * <p>名字里的 {@code test} 命名空间是刻意的: 它属于"接入方", 不属于平台。
     */
    @DomainType("test.finish-experiment")
    public record FinishExperiment(String detail) implements Intention {

        @Override
        public IntentionId id() {
            return IntentionId.of("test.finish-experiment");
        }

        @Override
        public String description() {
            return "把今天的实验做完";
        }

        @Override
        public IntentionPriority priority() {
            return IntentionPriority.IMPORTANT;
        }

        @Override
        public Set<String> requiredCapabilities() {
            return Set.of("lab.access");
        }

        @Override
        public Feasibility evaluate(IntentionContext context) {
            if (context.can("lab.access")) {
                return Feasibility.yes();
            }
            return Feasibility.no("她进不去实验室", "lab.access");
        }
    }

    private static IntentionContext contextWith(boolean canEnterLab) {
        IntentionContext base = IntentionContext.minimal(T);
        return canEnterLab ? base.withCapabilities(Set.of("lab.access")) : base;
    }

    @Test
    @DisplayName("第三方意图可以在运行时注册, 不需要改平台源码")
    void 第三方意图运行时注册() {
        IntentionRegistry registry = new IntentionRegistry();

        registry.register(FinishExperiment.class, () -> new FinishExperiment("第 7 组"));

        assertTrue(registry.resolve("test.finish-experiment.v1").isPresent(),
                "注册之后解析不到 —— 注册表没有把 @DomainType 的名字规范化成 .v1 的形式");
        assertEquals(FinishExperiment.class, registry.resolve("test.finish-experiment.v1").orElseThrow());
        assertEquals(1, registry.size());
    }

    @Test
    @DisplayName("注册之后能造出实例, 而且它真的参与可行性判断")
    void 造出实例并参与判断() {
        IntentionRegistry registry = new IntentionRegistry();
        registry.register(FinishExperiment.class, () -> new FinishExperiment("第 7 组"));

        Optional<Intention> created = registry.create("test.finish-experiment.v1");
        assertTrue(created.isPresent(), "造不出实例 —— 意图注册表就只是一个名字表");

        Intention intention = created.get();
        assertEquals("把今天的实验做完", intention.description());
        assertTrue(intention.evaluate(contextWith(true)).feasible(),
                "能进实验室时她应当觉得这件事做得成");
        assertFalse(intention.evaluate(contextWith(false)).feasible(),
                "进不去实验室时她必须觉得做不成, 并说明缺什么");
        assertEquals(List.of("lab.access"), intention.evaluate(contextWith(false)).missing());
    }

    @Test
    @DisplayName("注册表认得出一个意图的类型 id")
    void 认得出类型id() {
        IntentionRegistry registry = new IntentionRegistry();
        registry.register(FinishExperiment.class, () -> new FinishExperiment("第 7 组"));

        assertEquals("test.finish-experiment.v1",
                registry.typeIdOf(new FinishExperiment("第 7 组")).orElseThrow());
        assertEquals(List.of("test.finish-experiment.v1"), registry.under("test"));
        assertTrue(registry.typeIds().contains("test.finish-experiment.v1"));
    }

    @Test
    @DisplayName("没有工厂的登记项会被列出来, 而不是在运行时静默失败")
    void 没有工厂的登记项会被列出来() {
        IntentionRegistry registry = new IntentionRegistry();

        registry.register(Unbuildable.class);

        assertEquals(1, registry.size());
        assertEquals(List.of("test.unbuildable.v1"), registry.withoutFactory(),
                "登记了却不列出来 —— 那么'这个意图为什么从来没出现过'只能靠人去读代码");
        assertTrue(registry.create("test.unbuildable.v1").isEmpty(),
                "一个没有工厂、也没有无参构造的意图不该被凭空造出来");
    }

    /** 一个**故意**造不出实例的意图 —— 用来钉住"登记项 vs 可构造项"这两个概念。 */
    @DomainType("test.unbuildable")
    public record Unbuildable(String needsAnArgument) implements Intention {

        @Override
        public IntentionId id() {
            return IntentionId.of("test.unbuildable");
        }

        @Override
        public String description() {
            return "一个造不出来的念头";
        }

        @Override
        public IntentionPriority priority() {
            return IntentionPriority.TRIVIAL;
        }

        @Override
        public Feasibility evaluate(IntentionContext context) {
            return Feasibility.yes();
        }
    }

    @Test
    @DisplayName("意图是接口, 不是枚举 —— 这一条是整段设计的地基")
    void 意图是接口() {
        assertTrue(Intention.class.isInterface(),
                "Intention 必须是接口。它是'她想做的事'的开放集合 —— "
                        + "接入一个新应用就有了新种类, 而枚举只能由平台作者改");
        assertFalse(Intention.class.isEnum());
        assertFalse(IntentionPriority.class.isEnum(),
                "IntentionPriority 是 record 不是枚举: 它是一个可以被计算、被插值的量, "
                        + "不是一个分类。见它的类注释里关于 §1.3 判据的那一段");
    }
}
