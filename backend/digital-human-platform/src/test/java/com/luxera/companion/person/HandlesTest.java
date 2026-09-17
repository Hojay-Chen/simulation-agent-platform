package com.luxera.companion.person;

import com.luxera.companion.common.BusinessException;
import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 账号ID 的**形状规则** —— 生成器与校验器必须对同一套规则达成一致。
 *
 * <p>为什么这一组值得单独存在: 这两件事若各写一份, 迟早会出现"生成器造得出、校验器不认"
 * 的输入, 表现为随机的 400, 而且只在撞上那个特定字符时才复现。这里对每一条规则都同时
 * 断言两侧 —— 合法的能被 {@code validate} 接受, 非法的被拒。
 */
class HandlesTest {

    // ── 归一化 ────────────────────────────────────────────────────────────

    @Test
    void normalizationLowercasesSoThatCaseNeverSplitsOneAccountIntoTwo() {
        assertEquals("xiaoman", Handles.normalize("XiaoMan"));
        assertEquals("xiaoman", Handles.normalize("  XIAOMAN  "));
        assertEquals("", Handles.normalize(null));
    }

    /**
     * 土耳其语陷阱: 默认 locale 下 {@code "I".toLowerCase()} 是带点的 {@code ı},
     * 而那个字符过不了形状校验 —— 于是同一个输入在开发机上合法、在另一台机器上非法。
     */
    @Test
    void normalizationIsLocaleIndependent() {
        assertEquals("i", Handles.normalize("I"));
        assertEquals(Handles.normalize("I"), Handles.normalize("i"));
    }

    // ── 校验: 合法的 ──────────────────────────────────────────────────────

    @Test
    void acceptsTheShapesWeTellUsersToUse() {
        for (String ok : new String[]{"xiaoman", "xiaoman_01", "xiao-man", "a12345", "abcdef", "ab".repeat(12)}) {
            assertEquals(ok, Handles.validate(ok), ok + " 应当合法");
        }
    }

    @Test
    void acceptedShapesAreNormalizedNotRejected() {
        assertEquals("xiaoman", Handles.validate("XiaoMan"), "大写应当归一成小写, 而不是报错");
        assertEquals("xiaoman", Handles.validate("  xiaoman\n"));
    }

    // ── 校验: 非法的 ──────────────────────────────────────────────────────

    @Test
    void rejectsEmptyAndWrongLength() {
        assertThrows(BusinessException.class, () -> Handles.validate(""));
        assertThrows(BusinessException.class, () -> Handles.validate(null));
        assertThrows(BusinessException.class, () -> Handles.validate("abcde"), "5 个字符太短");
        assertThrows(BusinessException.class, () -> Handles.validate("a".repeat(25)), "25 个字符太长");
    }

    @Test
    void rejectsCharactersThatWouldBeMistypedOrAmbiguous() {
        // 中文: 账号ID 要被念出来、手打出来, 中文没法在不说明的情况下敲对
        assertThrows(BusinessException.class, () -> Handles.validate("小满"));
        assertThrows(BusinessException.class, () -> Handles.validate("xiao man"), "空格");
        assertThrows(BusinessException.class, () -> Handles.validate("xiao.man"), "点");
        assertThrows(BusinessException.class, () -> Handles.validate("xiao@man"), "@");
    }

    /** **必须字母开头** —— 这条规则把账号ID 与手机号、UUID 在视觉上分开。 */
    @Test
    void rejectsLeadingDigitUnderscoreOrHyphen() {
        assertThrows(BusinessException.class, () -> Handles.validate("13800138000"));
        assertThrows(BusinessException.class, () -> Handles.validate("_xiaoman"));
        assertThrows(BusinessException.class, () -> Handles.validate("-xiaoman"));
        assertThrows(BusinessException.class, () -> Handles.validate("8a4b2c1d-3e5f-4a6b-8c7d-9e0f1a2b3c4d"));
    }

    /** 保留字只做**全等**匹配: 挡的是"看起来就是官方的那个", 不是"含有某个词的"。 */
    @Test
    void rejectsReservedWordsButOnlyExactly() {
        for (String reserved : new String[]{"admin", "official", "system", "luxera", "me", "support"}) {
            assertThrows(BusinessException.class, () -> Handles.validate(reserved), reserved + " 是保留字");
        }
        assertEquals("admin01", Handles.validate("admin01"), "含保留字但是另一个东西, 合法");
        assertEquals("myadmin", Handles.validate("myadmin"));
    }

    /** 保留字大小写不敏感 —— 否则 {@code Admin} 就绕过去了。 */
    @Test
    void reservedWordsAreCaseInsensitive() {
        assertThrows(BusinessException.class, () -> Handles.validate("ADMIN"));
        assertThrows(BusinessException.class, () -> Handles.validate("Official"));
    }

    // ── 生成 ──────────────────────────────────────────────────────────────

    @Test
    void generatedHandlesAlwaysPassValidation() {
        Random r = new Random(20260917L);
        for (int i = 0; i < 500; i++) {
            String h = Handles.generate(r);
            assertEquals(h, Handles.validate(h), "生成器造出了校验器不认的东西: " + h);
        }
    }

    /** 字母表刻意剔除了 {@code 0 o 1 l i} —— 它们在任何界面里都可能被念错。 */
    @Test
    void generatedHandlesAvoidCharactersThatLookAlike() {
        Random r = new Random(7L);
        for (int i = 0; i < 500; i++) {
            String h = Handles.generate(r);
            for (char c : new char[]{'0', 'o', '1', 'l', 'i'}) {
                assertFalse(h.indexOf(c) >= 0, "生成结果里出现了易混字符 " + c + ": " + h);
            }
        }
    }

    @Test
    void generatedHandlesDiffer() {
        Random r = new Random(1L);
        assertNotEquals(Handles.generate(r), Handles.generate(r));
    }

    // ── 建议 ──────────────────────────────────────────────────────────────

    /**
     * {@code suggestFrom} 的契约是"返回一个**必然**能通过 {@code validate} 的字符串"。
     *
     * <p>这条断言比它看起来重要: 界面会把建议直接填进输入框, 用户点保存。一个通不过校验的
     * 建议会变成"我照它说的做了, 它还是拒绝我" —— 比不给建议更糟。
     */
    @Test
    void suggestionsAreAlwaysAcceptable() {
        String[] nasty = {"小满", "", "   ", "!!", "13800138000", "a", "x".repeat(40),
                "admin", "____", "小满_01", "A", "我我我我我我"};
        for (String raw : nasty) {
            String s = Handles.suggestFrom(raw);
            assertEquals(s, Handles.validate(s), "对 " + raw + " 给出了通不过校验的建议: " + s);
        }
    }

    /** 擦得出来时应当**看得出用户原本想要什么**, 而不是甩一个纯随机串。 */
    @Test
    void suggestionsKeepTheUsersIntentWhenPossible() {
        assertTrue(Handles.suggestFrom("XiaoMan").startsWith("xiaoman"));
        assertTrue(Handles.suggestFrom("xiaoman!!!").startsWith("xiaoman"));
    }

    @Test
    void variantSuggestionsStayWithinLengthLimit() {
        Random r = new Random(3L);
        // 基名已经顶到长度上限 —— 加后缀必须截短基名, 否则给出一个超过 24 位的建议
        String atLimit = Handles.suggestVariant("a".repeat(24), r);
        assertEquals(atLimit, Handles.validate(atLimit), "变体建议必须自己合法, 否则用户提交又被拒");
        assertTrue(atLimit.startsWith("a".repeat(10)), "截短是必要的, 但应当尽量保留原意");

        String shortBase = Handles.suggestVariant("xiaoman", r);
        assertEquals(shortBase, Handles.validate(shortBase));
        assertTrue(shortBase.startsWith("xiaoman_"), "短基名不该被无谓地截短");
    }
}
