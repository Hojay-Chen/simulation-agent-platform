package com.luxera.companion.interaction;

import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * 回复延迟引擎(设计文档 §十二/十三): 延迟不是 random, 而是由消息价值/情绪/状态/时间计算。
 * 用户可接受的"打字/思考"延迟, 让"秒回"消失。
 */
@Component
public class ResponseLatencyEngine {

    public long computeDelayMs(InteractionDecision decision, String userText,
                               double energy, double stress, LocalDateTime now) {
        return computeDelayMs(decision, userText, energy, stress, now, null);
    }

    /** P1: 加入"她此刻可用状态" —— 忙/休息/走神会回得更慢, 更真实 */
    public long computeDelayMs(InteractionDecision decision, String userText,
                               double energy, double stress, LocalDateTime now,
                               com.luxera.companion.state.CompanionAvailability availability) {
        long base = switch (decision.commitment) {
            case ACK -> rand(400, 1500);
            case CASUAL -> rand(1000, 4000);
            case ENGAGED -> rand(2000, 7000);
            case DEEP -> rand(3000, 12000);
        };
        // Agent 忙/累 → 更慢
        if (energy < 0.4) base += rand(800, 2500);
        if (stress > 0.6) base += rand(500, 1500);
        // P1: 可用状态影响节奏(她也有自己的生活)
        if (availability != null) {
            switch (availability) {
                case BUSY -> base += rand(2000, 5000);
                case RESTING -> base += rand(1000, 3000);
                case DISTRACTED -> base += rand(800, 2000);
                case SLEEPING -> base += rand(5000, 12000);
                case SOCIALIZING, TRAVELING -> base += rand(1500, 4000);
                default -> { }
            }
        }
        // 长消息 → 略慢
        if (userText != null && userText.length() > 40) base += rand(300, 1200);
        // 深夜 → 略慢
        int h = now.getHour();
        if (h >= 0 && h < 7) base += 500;
        return clamp(base, 300, 30000);
    }

    private static long rand(int lo, int hi) {
        return lo + (long) (Math.random() * (hi - lo));
    }

    private static long clamp(long v, long lo, long hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}
