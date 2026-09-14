package com.luxera.companion.digitalhuman.persona;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * V10 §5.8 PersonaFingerprint 测试: 同 persona 同指纹, 不同 persona 不同指纹。
 */
class PersonaFingerprintTest {

    private final PersonaFingerprint fingerprint = new PersonaFingerprint();

    private PersonaVoiceProfile profile(String accent, String... catchphrases) {
        PersonaVoiceProfile p = new PersonaVoiceProfile();
        p.accent = accent;
        p.catchphrases = java.util.List.of(catchphrases);
        p.emojiUsageRate = 0.3;
        p.sentenceEndingStyle = "不加语气词";
        return p;
    }

    @Test
    void samePersonaYieldsSameFingerprint() {
        PersonaVoiceProfile p1 = profile("偶尔用哎呀开头", "没事", "还行");
        PersonaVoiceProfile p2 = profile("偶尔用哎呀开头", "没事", "还行");
        assertEquals(fingerprint.compute(p1), fingerprint.compute(p2));
    }

    @Test
    void differentCatchphraseYieldsDifferentFingerprint() {
        PersonaVoiceProfile p1 = profile("偶尔用哎呀开头", "没事", "还行");
        PersonaVoiceProfile p2 = profile("偶尔用哎呀开头", "没事", "真不错");
        assertNotEquals(fingerprint.compute(p1), fingerprint.compute(p2));
    }

    @Test
    void injectFingerprintPrefixesPrompt() {
        String result = fingerprint.injectFingerprint("你好", "ab12cd34");
        assertTrue(result.startsWith("[PersonaFingerprint fp=ab12cd34]"));
        assertTrue(result.contains("你好"));
    }

    @Test
    void fingerprintIs16HexChars() {
        String fp = fingerprint.compute(profile("测试", "口头禅1"));
        assertEquals(16, fp.length());
        assertTrue(fp.matches("[0-9a-f]{16}"), "指纹应为 16 位十六进制, 实际: " + fp);
    }
}