package com.luxera.companion.digitalhuman.persona;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * V10 §5.7 Persona Voice Compiler: 口音/口头禅/微习惯/词汇偏好编译。
 *
 * per persona 编译 voice profile(缓存), 注入 ConversationRequest stablePrefix。
 * 结合 PersonaFingerprint 防止不同 persona 表达趋同。
 */
@Slf4j
@Component
public class PersonaVoiceCompiler {

    private final ConcurrentHashMap<String, PersonaVoiceProfile> cache = new ConcurrentHashMap<>();

    /** 编译 voice profile(首次计算后缓存) */
    public PersonaVoiceProfile compile(String companionId, JsonNode personaJson) {
        return cache.computeIfAbsent(companionId, id -> {
            PersonaVoiceProfile profile = new PersonaVoiceProfile();
            profile.companionId = companionId;

            if (personaJson != null) {
                // accent
                if (personaJson.has("accent")) {
                    profile.accent = personaJson.get("accent").asText();
                }
                // catchphrases
                if (personaJson.has("catchphrases")) {
                    List<String> list = new ArrayList<>();
                    personaJson.get("catchphrases").forEach(n -> list.add(n.asText()));
                    profile.catchphrases = list;
                }
                // microHabits
                if (personaJson.has("microHabits")) {
                    List<String> list = new ArrayList<>();
                    personaJson.get("microHabits").forEach(n -> list.add(n.asText()));
                    profile.microHabits = list;
                }
                // vocabularyHints
                if (personaJson.has("vocabularyHints")) {
                    List<String> list = new ArrayList<>();
                    personaJson.get("vocabularyHints").forEach(n -> list.add(n.asText()));
                    profile.vocabularyHints = list;
                }
                // emojiUsageRate
                if (personaJson.has("emojiUsageRate")) {
                    profile.emojiUsageRate = personaJson.get("emojiUsageRate").asDouble();
                }
                // sentenceEndingStyle
                if (personaJson.has("sentenceEndingStyle")) {
                    profile.sentenceEndingStyle = personaJson.get("sentenceEndingStyle").asText();
                }
            }

            // 默认值
            if (profile.accent == null) profile.accent = "";
            if (profile.catchphrases == null) profile.catchphrases = List.of();
            if (profile.microHabits == null) profile.microHabits = List.of();
            if (profile.vocabularyHints == null) profile.vocabularyHints = List.of();
            if (profile.emojiUsageRate == 0) profile.emojiUsageRate = 0.2;
            if (profile.sentenceEndingStyle == null) profile.sentenceEndingStyle = "";

            return profile;
        });
    }

    /** 生成 stablePrefix 注入片段 */
    public String buildVoiceFragment(PersonaVoiceProfile profile) {
        StringBuilder sb = new StringBuilder();

        if (profile.accent != null && !profile.accent.isBlank()) {
            sb.append("[人物口音] ").append(profile.accent).append("\n");
        }
        if (!profile.catchphrases.isEmpty()) {
            sb.append("[口头禅] 常用 ").append(String.join("、", profile.catchphrases.subList(0, Math.min(3, profile.catchphrases.size())))).append("\n");
        }
        if (!profile.microHabits.isEmpty()) {
            sb.append("[微习惯] ").append(profile.microHabits.get(0)).append("\n");
        }
        if (!profile.vocabularyHints.isEmpty()) {
            sb.append("[词汇偏好] ").append(profile.vocabularyHints.get(0)).append("\n");
        }
        if (profile.emojiUsageRate > 0) {
            sb.append("[Emoji 使用率] ").append(String.format("%.0f%%", profile.emojiUsageRate * 100)).append("\n");
        }
        if (profile.sentenceEndingStyle != null && !profile.sentenceEndingStyle.isBlank()) {
            sb.append("[句尾风格] ").append(profile.sentenceEndingStyle).append("\n");
        }

        return sb.toString();
    }
}