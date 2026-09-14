package com.luxera.companion.life;

import com.luxera.companion.config.CurrentUser;
import com.luxera.companion.persona.CompanionService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/** "她今天在干嘛" / 连续生活(设计文档 §32) */
@RestController
@RequestMapping("/api/companions/{companionId}/life")
public class LifeController {

    private final CompanionLifeService lifeService;
    private final LifeContextProvider contextProvider;
    private final LifeRuntime lifeRuntime;
    private final CompanionService companionService;
    private final CurrentUser currentUser;

    public LifeController(CompanionLifeService lifeService, LifeContextProvider contextProvider,
                          LifeRuntime lifeRuntime, CompanionService companionService, CurrentUser currentUser) {
        this.lifeService = lifeService;
        this.contextProvider = contextProvider;
        this.lifeRuntime = lifeRuntime;
        this.companionService = companionService;
        this.currentUser = currentUser;
    }

    @GetMapping
    public Map<String, Object> life(@PathVariable String companionId) {
        String userId = currentUser.requireUserId();
        var companion = companionService.requireOwned(userId, companionId);
        // 先推进到当前时刻, 保证"她此刻在干嘛"是新鲜的
        lifeRuntime.tick(companionId, java.time.LocalDateTime.now());
        CompanionLife life = lifeService.getOrCreate(companionId);
        List<LifeActivity> today = contextProvider.todayActivities(companionId);
        return Map.of(
                "currentActivity", life.getCurrentActivity(),
                "currentLocation", life.getCurrentLocation(),
                "dayPhase", life.getDayPhase(),
                "todaySummary", contextProvider.describeToday(companionId, companion.getName()),
                "todayActivities", today
        );
    }

    @GetMapping("/today")
    public String today(@PathVariable String companionId) {
        String userId = currentUser.requireUserId();
        var companion = companionService.requireOwned(userId, companionId);
        return contextProvider.describeToday(companionId, companion.getName());
    }
}
