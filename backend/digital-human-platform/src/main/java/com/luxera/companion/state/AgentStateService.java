package com.luxera.companion.state;

import com.luxera.companion.runtime.EmotionDelta;
import com.luxera.companion.runtime.EmotionReducer;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

@Service
public class AgentStateService {

    private final AgentStateRepository repo;
    private final EmotionReducer emotionReducer;

    public AgentStateService(AgentStateRepository repo, EmotionReducer emotionReducer) {
        this.repo = repo;
        this.emotionReducer = emotionReducer;
    }

    /** 情绪增量经 StateReducer 应用到状态(唯一入口) */
    @Transactional
    public AgentState applyEmotionDelta(String companionId, EmotionDelta delta) {
        AgentState s = getOrCreate(companionId);
        return emotionReducer.apply(s, delta);
    }

    /** 持久化状态 */
    @Transactional
    public AgentState save(AgentState state) {
        return repo.save(state);
    }

    @Transactional
    public AgentState getOrCreate(String companionId) {
        return repo.findByCompanionId(companionId).orElseGet(() -> {
            AgentState s = new AgentState();
            s.setCompanionId(companionId);
            return repo.save(s);
        });
    }

    @Transactional
    public AgentState get(String companionId) {
        return repo.findByCompanionId(companionId).orElse(null);
    }

    /** 每次对话后状态微演化(人格不变,状态可变;设计文档 36-37 节) */
    @Transactional
    public void onMessage(String companionId, String emotion) {
        AgentState s = getOrCreate(companionId);
        s.setEnergy(clamp(s.getEnergy() - 0.004));
        s.setStress(clamp(s.getStress() + 0.001));
        s.setSocialEnergy(clamp(s.getSocialEnergy() - 0.002));
        s.setEmotionalCloseness(clamp(s.getEmotionalCloseness() + 0.0009));
        s.setCuriosity(clamp(s.getCuriosity() + 0.001));
        if (emotion != null) {
            s.setMood(moodFor(emotion));
        }
        repo.save(s);
    }

    @Transactional
    public void applyEvent(String companionId, String eventType, double intensity) {
        AgentState s = getOrCreate(companionId);
        switch (eventType) {
            case "negative" -> {
                s.setStress(clamp(s.getStress() + intensity));
                s.setEnergy(clamp(s.getEnergy() - intensity * 0.6));
                s.setMood("低落的");
            }
            case "positive" -> {
                s.setEnergy(clamp(s.getEnergy() + intensity * 0.5));
                s.setStress(clamp(s.getStress() - intensity * 0.3));
                s.setMood("轻快的");
            }
            default -> { }
        }
        repo.save(s);
    }

    /** Appraisal: 消息改变内部状态(hurt/anger 累积, warmth 增进亲密度/平复情绪) */
    @Transactional
    public void applyAppraisal(String companionId, double hurt, double anger, double warmth) {
        AgentState s = getOrCreate(companionId);
        if (hurt > 0) {
            s.setHurt(clamp(s.getHurt() + hurt));
            s.setMood("有点受伤");
        }
        if (anger > 0) {
            s.setAnger(clamp(s.getAnger() + anger));
            s.setStress(clamp(s.getStress() + anger * 0.3));
            s.setMood("有点生气");
        }
        if (warmth > 0) {
            s.setHurt(clamp(s.getHurt() - warmth * 0.5));
            s.setAnger(clamp(s.getAnger() - warmth * 0.5));
            s.setEmotionalCloseness(clamp(s.getEmotionalCloseness() + warmth * 0.02));
            if (s.getHurt() < 0.2 && s.getAnger() < 0.2) {
                s.setMood("平静的");
            }
        }
        repo.save(s);
    }

    /** hurt/anger/sadness/anxiety 随时间衰减(状态愈合) */
    @Transactional
    public void decayNegative(String companionId, double rate) {
        repo.findByCompanionId(companionId).ifPresent(s -> {
            s.setHurt(clamp(s.getHurt() - rate));
            s.setAnger(clamp(s.getAnger() - rate));
            s.setSadness(clamp(s.getSadness() - rate));
            s.setAnxiety(clamp(s.getAnxiety() - rate));
            s.setWarmth(clamp(s.getWarmth() - rate * 0.2));
            repo.save(s);
        });
    }

    /** 全部伴侣的负面情绪衰减(定时任务调用, 负面情绪会随时间自然愈合) */
    @Transactional
    public void decayAllNegative(double rate) {
        for (AgentState s : repo.findAll()) {
            boolean changed = false;
            // §48 状态惯性: 按比例衰减(情绪越高消退越慢, 保留"还有一点不爽"的残余感)
            if (s.getHurt() > 0.001) {
                s.setHurt(clamp(s.getHurt() - Math.max(rate, s.getHurt() * 0.15) * 0.5));
                changed = true;
            }
            if (s.getAnger() > 0.001) {
                s.setAnger(clamp(s.getAnger() - Math.max(rate, s.getAnger() * 0.15) * 0.5));
                changed = true;
            }
            if (s.getSadness() > 0.001) {
                s.setSadness(clamp(s.getSadness() - Math.max(rate, s.getSadness() * 0.15) * 0.5));
                changed = true;
            }
            if (s.getAnxiety() > 0.001) {
                s.setAnxiety(clamp(s.getAnxiety() - Math.max(rate, s.getAnxiety() * 0.15) * 0.5));
                changed = true;
            }
            if (s.getLoneliness() > 0.001) {
                s.setLoneliness(clamp(s.getLoneliness() - Math.max(rate, s.getLoneliness() * 0.15) * 0.5));
                changed = true;
            }
            if (changed) {
                if (s.getHurt() < 0.15 && s.getAnger() < 0.15 && s.getSadness() < 0.15
                        && s.getAnxiety() < 0.15 && !"平静的".equals(s.getMood())) {
                    s.setMood("平静的");
                }
                repo.save(s);
            }
        }
    }

    private static String moodFor(String emotion) {
        switch (emotion == null ? "" : emotion) {
            case "sad": return "有点心疼";
            case "happy":
            case "excited": return "轻快的";
            case "anxious": return "有点担心";
            case "angry": return "有些气不过";
            case "tired": return "安静的";
            default: return "平静的";
        }
    }

    private static double clamp(double v) {
        return Math.max(0, Math.min(1, v));
    }
}
