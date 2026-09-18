package com.luxera.companion.human.mind;

import com.luxera.companion.boundary.event.SensoryEvent;
import com.luxera.companion.human.mind.percept.Modality;
import com.luxera.companion.human.mind.percept.Percept;
import com.luxera.companion.human.mind.percept.Perception;
import com.luxera.companion.human.mind.percept.SourceRef;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.*;

/**
 * V2.2 §3.4.3 —— <b>Percept 里没有"是谁发的"、"发了什么"、"重不重要"</b>。
 *
 * <h2>为什么这条要用反射来钉, 而不是靠读代码</h2>
 * 因为这条禁令的违反方式极其自然: 某天有人要做一个"这条消息来自主人"的判断,
 * 最顺手的地方就是给 {@code Percept} 加一个 {@code senderId}。那时编译器不会报错,
 * 已有的测试也不会变红, 而架构已经塌了一角 —— 那三个问题(是谁 / 说了什么 / 重不重要)
 * 一旦能在感知层被回答, {@code Relationship}、{@code ChatApplication} 与
 * {@code Attention} 就都失去了它们存在的理由。
 *
 * <p>所以这里断言的是<b>属性集合本身</b>: 多一个少一个都会红。加字段的人必须
 * 先删掉这个测试里的一个字面量 —— 而那时他会看到这段注释。
 */
class PerceptShapeTest {

    private static final Instant T = Instant.parse("2026-03-02T09:00:00Z");

    @Test
    @DisplayName("感知恰好有那七件事, 不多一件")
    void 感知的访问器恰好是那七个() {
        Set<String> actual = new TreeSet<>();
        for (Method m : Percept.class.getMethods()) {
            if (m.getDeclaringClass() == Object.class || m.isSynthetic()) {
                continue;
            }
            actual.add(m.getName());
        }
        Set<String> expected = new TreeSet<>(Set.of(
                "id", "modality", "content", "salience", "urgency", "source", "occurredAt",
                "describe"));
        assertEquals(expected, actual,
                "Percept 的属性集合变了。§3.4.3 说它只有这七件事 —— 加第八件之前请先读这段注释");
    }

    @Test
    @DisplayName("感知里没有发送者、没有正文、没有重要性")
    void 感知里没有被禁止的那三类信息() {
        // 这三类词根分别对应 §3.4.3 里被赶去别处的三个问题:
        //   是谁发的 → Relationship; 发了什么 → ChatApplication; 重不重要 → Attention
        String[] forbidden = {
                "sender", "from", "account", "person", "author", "who",
                "message", "text", "body", "payload", "contentRef",
                "importance", "important", "priority", "relevance", "salientToHer",
                "unread", "count", "read",
        };
        for (Method m : Percept.class.getMethods()) {
            if (m.getDeclaringClass() == Object.class) {
                continue;
            }
            String name = m.getName().toLowerCase();
            for (String bad : forbidden) {
                assertFalse(name.contains(bad),
                        "Percept 上出现了 " + m.getName() + " —— 它看起来在回答 \"" + bad
                                + "\" 这一类问题。§3.4.3: 是谁发的属于 Relationship, "
                                + "发了什么属于 ChatApplication, 重不重要属于 Attention。"
                                + "感知只做解释, 不做判断");
            }
        }
    }

    @Test
    @DisplayName("七个属性各自的类型就是文档写的那种")
    void 属性类型对得上() {
        Map<String, String> types = new LinkedHashMap<>();
        for (Method m : Percept.class.getMethods()) {
            if (m.getDeclaringClass() != Percept.class) {
                continue;
            }
            types.put(m.getName(), m.getReturnType().getSimpleName());
        }
        assertEquals("PerceptId", types.get("id"));
        assertEquals("Modality", types.get("modality"));
        assertEquals("String", types.get("content"));
        assertEquals("double", types.get("salience"));
        assertEquals("double", types.get("urgency"));
        assertEquals("SourceRef", types.get("source"));
        assertEquals("Instant", types.get("occurredAt"));
    }

    @Test
    @DisplayName("显著度由刺激本身算出来, 不看她的处境 —— 参数表里没有她")
    void 显著度公式的参数里没有她() {
        Method salienceOf;
        try {
            salienceOf = Perception.class.getMethod("salienceOf", SensoryEvent.class, Modality.class);
        } catch (NoSuchMethodException e) {
            fail("Perception.salienceOf 的签名变了。它必须是 (SensoryEvent, Modality) —— "
                    + "§3.4.4 的边界铁律要求显著度只由消息决定");
            return;
        }
        assertTrue(Modifier.isStatic(salienceOf.getModifiers()));
        for (Class<?> parameter : salienceOf.getParameterTypes()) {
            String name = parameter.getSimpleName().toLowerCase();
            assertFalse(name.contains("context") || name.contains("relationship")
                            || name.contains("attention") || name.contains("human"),
                    "显著度公式收进了 " + parameter.getSimpleName()
                            + " —— 那就是把处境也算了进去, 于是处境会被罚两次");
        }
    }

    @Test
    @DisplayName("显著度与紧迫度都被夹在 0 到 1 之间")
    void 取值范围被夹住() {
        Percept percept = Perception.percept(Modality.AUDITORY, "很响的一声", 9.9, -3.0,
                SourceRef.ofObject("device-1"), T);
        assertTrue(percept.salience() >= 0.0 && percept.salience() <= 1.0,
                "显著度跑到 [0,1] 之外了: " + percept.salience());
        assertTrue(percept.urgency() >= 0.0 && percept.urgency() <= 1.0,
                "紧迫度跑到 [0,1] 之外了: " + percept.urgency());
    }

    @Test
    @DisplayName("感知是接口, 而且不是枚举")
    void 感知是接口() {
        assertTrue(Percept.class.isInterface(), "Percept 必须是接口 —— 它是词汇, 不是取值表");
        assertFalse(Percept.class.isEnum(), "感知不是枚举");
    }
}
