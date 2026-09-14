package com.luxera.companion.runtime.agent.event;

import com.luxera.companion.experience.ExperienceProcessor;
import com.luxera.companion.life.CompanionLife;
import com.luxera.companion.life.CompanionLifeService;
import com.luxera.companion.memory.Memory;
import com.luxera.companion.memory.MemoryService;
import com.luxera.companion.persona.Companion;
import com.luxera.companion.persona.CompanionRepository;
import com.luxera.companion.persona.CompanionService;
import com.luxera.companion.persona.Persona;
import com.luxera.companion.runtime.AgentTraceService;
import com.luxera.companion.runtime.WorldEvent;
import com.luxera.companion.runtime.WorldEventLogService;
import com.luxera.companion.runtime.WorldEventType;
import com.luxera.companion.state.AgentState;
import com.luxera.companion.state.AgentStateService;
import com.luxera.companion.thought.ThoughtService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.stream.Collectors;

/**
 * 事件模拟器(§28/§70/§71): LLM 只提出候选, 真正是否发生由 Runtime 决定。
 * 确定性基概率 + 人格/环境/记忆/近期事件 modifier + 加权采样。
 * 事件不是随机制造剧情 —— NORMAL(无事发生)默认概率最高。
 */
@Slf4j
@Component
public class EventSimulator {

    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("HH:mm");
    private static final Random RANDOM = new Random();

    private final EventSimulationAgent agent;
    private final CompanionLifeService lifeService;
    private final AgentStateService stateService;
    private final CompanionService companionService;
    private final CompanionRepository companionRepository;
    private final MemoryService memoryService;
    private final WorldEventLogService worldEventLogService;
    private final AgentTraceService traceService;
    private final EventChainService eventChainService;

    public EventSimulator(EventSimulationAgent agent, CompanionLifeService lifeService,
                          AgentStateService stateService, CompanionService companionService,
                          CompanionRepository companionRepository,
                          MemoryService memoryService, WorldEventLogService worldEventLogService,
                          AgentTraceService traceService, EventChainService eventChainService) {
        this.agent = agent;
        this.lifeService = lifeService;
        this.stateService = stateService;
        this.companionService = companionService;
        this.companionRepository = companionRepository;
        this.memoryService = memoryService;
        this.worldEventLogService = worldEventLogService;
        this.traceService = traceService;
        this.eventChainService = eventChainService;
    }

    /** 模拟一次事件(通常由定时任务在休闲/通勤/晚间调用, 不在睡觉时调用) */
    @Transactional
    public String simulate(String companionId, LocalDateTime now) {
        CompanionLife life = lifeService.getOrCreate(companionId);
        AgentState state = stateService.get(companionId);
        Persona persona = companionService.getPersona(companionId);
        String personality = persona != null && persona.getPersonality() != null
                ? persona.getPersonality().getSummary() : null;
        String userId = companionRepository.findById(companionId)
                .map(Companion::getUserId).orElse(null);

        List<String> recentEvents = worldEventLogService.recent(companionId, 20).stream()
                .map(e -> e.getEventType()).collect(Collectors.toList());

        List<Memory> memories = memoryService.retrieve(userId, companionId,
                life.getCurrentActivity() == null ? "" : life.getCurrentActivity(), 5);

        EventSimulationContext ctx = new EventSimulationContext(
                companionId,
                life.getCurrentActivity(),
                environmentDesc(now),
                personality,
                state != null ? state.getEnergy() : 0.6,
                state != null ? state.getStress() : 0.3,
                state != null ? state.getMood() : "平静",
                memories,
                null,
                now.format(FMT),
                recentEvents);

        EventSimulationResult result = agent.execute(ctx);
        if (result.candidates().isEmpty()) return EventSimulationResult.NORMAL;

        EventSimulationResult.EventCandidate chosen = sample(result.candidates(), recentEvents, state);
        if (chosen == null || EventSimulationResult.NORMAL.equals(chosen.eventType())) {
            worldEventLogService.record(WorldEvent.of(WorldEventType.WORLD_EVENT_OCCURRED,
                    companionId, Map.of("event", "NORMAL", "at", now.toString())));
            return EventSimulationResult.NORMAL;
        }

        // 事件发生: 记录主事件 + §21 因果链(后果逐层应用, 深度 ≤ MAX_DEPTH)
        worldEventLogService.record(WorldEvent.of(WorldEventType.WORLD_EVENT_OCCURRED,
                companionId, Map.of("event", chosen.eventType(), "trigger", chosen.trigger(),
                        "consequences", chosen.consequences() == null ? List.of() : chosen.consequences(),
                        "at", now.toString())));
        eventChainService.applyChain(companionId, chosen.eventType(), chosen.consequences(), now);
        log.info("[事件模拟] {}: {}", companionId, chosen.eventType());
        return chosen.eventType();
    }

    /** 加权采样: 基概率 + modifier, 随机决定哪个事件发生 */
    EventSimulationResult.EventCandidate sample(List<EventSimulationResult.EventCandidate> candidates,
                                                List<String> recentEvents, AgentState state) {
        List<EventSimulationResult.EventCandidate> adjusted = new ArrayList<>();
        for (EventSimulationResult.EventCandidate c : candidates) {
            double p = c.probability();
            // 人格/状态 modifier(简单确定性调整)
            if (EventSimulationResult.FORGOT_UMBRELLA.equals(c.eventType())) {
                p = p * 0.8 + 0.02;   // 保持低位
            }
            if (EventSimulationResult.MEET_ACQUAINTANCE.equals(c.eventType())) {
                if (state != null && state.getEnergy() > 0.5) p += 0.03;  // 有精力更可能社交
            }
            if (EventSimulationResult.WORK_INTERRUPTION.equals(c.eventType())) {
                if (state != null && state.getStress() > 0.6) p += 0.05;  // 高压更容易被打断
            }
            // 近期事件抑制(避免重复)
            if (recentEvents != null && recentEvents.contains(c.eventType())) {
                p *= 0.3;
            }
            adjusted.add(new EventSimulationResult.EventCandidate(
                    c.eventType(), Math.max(0.0, Math.min(0.95, p)), c.trigger(), c.consequences()));
        }
        // 加权随机采样
        double total = adjusted.stream().mapToDouble(EventSimulationResult.EventCandidate::probability).sum();
        if (total <= 0) return null;
        double r = RANDOM.nextDouble() * total;
        double acc = 0;
        for (EventSimulationResult.EventCandidate c : adjusted) {
            acc += c.probability();
            if (r <= acc) return c;
        }
        return adjusted.get(adjusted.size() - 1);
    }

    private static String environmentDesc(LocalDateTime now) {
        int hour = now.getHour();
        if (hour >= 8 && hour < 18) return "白天";
        if (hour >= 18 && hour < 22) return "傍晚";
        return "夜晚";
    }
}
