package com.luxera.companion.digitalhuman.persona;

import java.util.ArrayList;
import java.util.List;

/**
 * V10 §5.7 Persona Voice Profile: 每个 persona 的口音/口头禅/微习惯/词汇偏好。
 * 编译后缓存, 注入 ConversationRequest stablePrefix, 配合 PersonaFingerprint 防趋同。
 */
public class PersonaVoiceProfile {

    public String companionId;
    public String accent = "";
    public List<String> catchphrases = new ArrayList<>();
    public List<String> microHabits = new ArrayList<>();
    public List<String> vocabularyHints = new ArrayList<>();
    public double emojiUsageRate = 0.2;
    public String sentenceEndingStyle = "";
}