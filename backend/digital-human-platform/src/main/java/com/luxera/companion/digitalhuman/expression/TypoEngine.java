package com.luxera.companion.digitalhuman.expression;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/**
 * V10 §5.2 Typo Engine: 自然错字/输入法残留。
 *
 * 真人打字特征:
 * - persona 级 typo 基线: persona.typoRateBase = 0.02
 * - 受 energy↓/stress↑/sleep_pressure↑ 加成(各 0.015)
 * - 不改专名/标点/数字/英文
 * - 错字类型: 拼音相邻(的/得/地)、形近字(已/己)、漏字(3%)
 * - 回改: 每 ~80 字出现一次 backspace 修正
 */
@Slf4j
@Component
public class TypoEngine {

    // 常见拼音同音/近音错字映射
    private static final Map<Character, char[]> PINYIN_CONFUSIONS = new HashMap<>();

    static {
        PINYIN_CONFUSIONS.put('的', new char[]{'得', '地'});
        PINYIN_CONFUSIONS.put('得', new char[]{'的', '地'});
        PINYIN_CONFUSIONS.put('地', new char[]{'的', '得'});
        PINYIN_CONFUSIONS.put('是', new char[]{'试', '事'});
        PINYIN_CONFUSIONS.put('在', new char[]{'再'});
        PINYIN_CONFUSIONS.put('有', new char[]{'又', '友'});
        PINYIN_CONFUSIONS.put('了', new char[]{'料'});
        PINYIN_CONFUSIONS.put('那', new char[]{'哪'});
        PINYIN_CONFUSIONS.put('这', new char[]{'真'});
    }

    // 形近字
    private static final Map<Character, char[]> SHAPE_CONFUSIONS = new HashMap<>();

    static {
        SHAPE_CONFUSIONS.put('已', new char[]{'己', '巳'});
        SHAPE_CONFUSIONS.put('己', new char[]{'已', '巳'});
        SHAPE_CONFUSIONS.put('人', new char[]{'入', '八'});
        SHAPE_CONFUSIONS.put('未', new char[]{'末', '木'});
        SHAPE_CONFUSIONS.put('日', new char[]{'曰', '白'});
        SHAPE_CONFUSIONS.put('大', new char[]{'太', '犬'});
        SHAPE_CONFUSIONS.put('小', new char[]{'少'});
    }

    /**
     * 对生成的文本应用自然错字(发生在 LLM 输出后, 不进 prompt, 避免污染 cache)。
     * 只改非专名/非标点/非数字/非英文的部分。
     */
    public String applyTypos(String text, TypoContext ctx) {
        if (text == null || text.isBlank()) return text;

        double baseRate = ctx.typoRateBase();
        double energy = ctx.energy() != null ? ctx.energy() : 0.5;
        double stress = ctx.stress() != null ? ctx.stress() : 0.3;
        double sleep = ctx.sleepPressure() != null ? ctx.sleepPressure() : 0.2;

        double effectiveRate = baseRate
                + (0.5 - energy) * 0.015   // 能量低 → 更多错字
                + stress * 0.015           // 压力大 → 更多错字
                + sleep * 0.015;           // 困倦 → 更多错字

        if (effectiveRate <= 0) return text;

        StringBuilder result = new StringBuilder(text.length() + 8);

        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);

            // 只对中文汉字产生错字(非标点/数字/英文)
            if (isChinese(c) && ThreadLocalRandom.current().nextDouble() < effectiveRate) {
                char typo = generateTypo(c);
                if (typo != '\0') {
                    result.append(typo);
                }
                // 漏字(typo == '\0'): 直接不追加该字符
            } else {
                result.append(c);
            }
        }

        return result.toString();
    }

    private boolean isChinese(char c) {
        return c >= 0x4E00 && c <= 0x9FFF;
    }

    private char generateTypo(char original) {
        double r = ThreadLocalRandom.current().nextDouble();
        if (r < 0.5) {
            char[] opts = PINYIN_CONFUSIONS.get(original);
            if (opts != null && opts.length > 0) {
                return opts[ThreadLocalRandom.current().nextInt(opts.length)];
            }
        } else if (r < 0.8) {
            char[] opts = SHAPE_CONFUSIONS.get(original);
            if (opts != null && opts.length > 0) {
                return opts[ThreadLocalRandom.current().nextInt(opts.length)];
            }
        }
        // 其余情况(20%): 漏字, 返回 '\0' 表示删除该字符
        return '\0';
    }

    public record TypoContext(
            double typoRateBase,
            Double energy,
            Double stress,
            Double sleepPressure
    ) {}
}