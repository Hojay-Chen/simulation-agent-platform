package com.luxera.companion.usermodel;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 用户聊天习惯学习(P1 §四十七~四十八): 增量统计用户的消息习惯。
 * 在 ChatController 每条用户消息入库后调用。轻量启发式, 不调用 LLM。
 * 用途: ①注入 ContextCompiler 让回复匹配节奏(不模仿); ②供 InteractionPolicy 微调预算。
 */
@Service
public class UserChatStyleService {

    private static final Pattern EMOJI = Pattern.compile(
            "[\\uD83C\\uDF00-\\uD83D\\uDE4F\\uD83D\\uDE80-\\uD83D\\uDEFF\\uD83E\\uDD00-\\uD83E\\uDDFF]");
    private static final Pattern LAUGH = Pattern.compile("(哈哈|呵呵|嘿嘿|hh|233|笑死|hhh|哈哈哈哈哈)");
    private static final Pattern QUESTION = Pattern.compile("[?？]");
    private static final long BURST_GAP_MS = 2000;

    private final UserChatStyleRepository repo;

    public UserChatStyleService(UserChatStyleRepository repo) {
        this.repo = repo;
    }

    /** 记录一条用户消息, 增量更新该伴侣的聊天习惯统计 */
    @Transactional
    public UserChatStyle record(String companionId, String userId, String text, LocalDateTime now) {
        UserChatStyle s = repo.findByCompanionId(companionId).orElseGet(() -> {
            UserChatStyle n = new UserChatStyle();
            n.setCompanionId(companionId);
            n.setUserId(userId);
            n.setHourDistribution(new HashMap<>());
            return repo.save(n);
        });

        int count = s.getSampleCount();
        double len = text == null ? 0 : text.length();
        s.setAvgMessageLength((s.getAvgMessageLength() * count + len) / (count + 1));

        if (s.getLastActiveAt() != null && now != null) {
            long gap = Duration.between(s.getLastActiveAt(), now).toMillis();
            if (gap > 0 && gap < 300_000) {
                s.setAvgGapMs((s.getAvgGapMs() * count + gap) / (count + 1));
            }
            boolean burst = gap > 0 && gap < BURST_GAP_MS;
            s.setBurstRate((s.getBurstRate() * count + (burst ? 1 : 0)) / (count + 1));
        }

        boolean emoji = text != null && EMOJI.matcher(text).find();
        boolean laugh = text != null && LAUGH.matcher(text).find();
        boolean question = text != null && QUESTION.matcher(text).find();
        s.setEmojiRate((s.getEmojiRate() * count + (emoji ? 1 : 0)) / (count + 1));
        s.setLaughRate((s.getLaughRate() * count + (laugh ? 1 : 0)) / (count + 1));
        s.setQuestionRate((s.getQuestionRate() * count + (question ? 1 : 0)) / (count + 1));

        // 活跃时段: 小时分布 + 首尾(忽略极端午夜单点)
        int hour = now == null ? 0 : now.getHour();
        Map<String, Object> dist = new HashMap<>();
        if (s.getHourDistribution() != null) dist.putAll(s.getHourDistribution());
        dist.merge(String.valueOf(hour), 1, (a, b) -> ((Number) a).intValue() + ((Number) b).intValue());
        s.setHourDistribution(dist);
        updateActiveWindow(s, hour, count + 1);

        s.setSampleCount(count + 1);
        s.setLastActiveAt(now == null ? LocalDateTime.now() : now);
        return repo.save(s);
    }

    /** 活跃时段: 取占比 >15% 且相邻的小时作为活跃窗口(简单实现: 众数小时 ±2) */
    private static void updateActiveWindow(UserChatStyle s, int hour, int total) {
        if (total < 3) return;
        Map<String, Object> dist = s.getHourDistribution();
        int peak = 0, peakHour = hour;
        for (Map.Entry<String, Object> e : dist.entrySet()) {
            int h = Integer.parseInt(e.getKey());
            int c = ((Number) e.getValue()).intValue();
            if (c > peak) {
                peak = c;
                peakHour = h;
            }
        }
        if (peak >= Math.max(2, total / 4)) {
            s.setActiveHourStart((peakHour + 23) % 24);
            s.setActiveHourEnd((peakHour + 1) % 24);
        }
    }

    @Transactional(readOnly = true)
    public UserChatStyle get(String companionId) {
        return repo.findByCompanionId(companionId).orElse(null);
    }

    /** 生成给 Prompt 的中文描述(她知道自己用户的聊天习惯, 但保持自己的性格) */
    public String describe(String companionId) {
        UserChatStyle s = get(companionId);
        if (s == null || s.getSampleCount() < 2) return null;
        StringBuilder sb = new StringBuilder();
        sb.append("他习惯发").append(Math.round(s.getAvgMessageLength())).append("字左右的消息");
        if (s.getBurstRate() > 0.3) sb.append(",经常一次发好几条");
        else if (s.getBurstRate() < 0.1) sb.append(",习惯一条条慢慢说");
        if (s.getEmojiRate() > 0.3) sb.append(",爱用表情");
        else if (s.getEmojiRate() < 0.05) sb.append(",很少用表情");
        if (s.getLaughRate() > 0.2) sb.append(",常'哈哈'");
        if (s.getQuestionRate() > 0.3) sb.append(",爱提问");
        else if (s.getQuestionRate() < 0.05) sb.append(",很少提问");
        if (s.getActiveHourStart() != null) {
            sb.append(",常在").append(s.getActiveHourStart()).append("-").append(s.getActiveHourEnd()).append("点找你");
        }
        sb.append("。你不需要模仿他的习惯,用你自己的方式和他相处。");
        return sb.toString();
    }
}
