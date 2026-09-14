package com.luxera.companion.selfmodel;

import com.luxera.companion.config.CurrentUser;
import com.luxera.companion.persona.CompanionService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** "她最近觉得自己怎样"(设计文档 §9 / §31 用户视角) */
@RestController
@RequestMapping("/api/companions/{companionId}/self")
public class SelfController {

    private final SelfModelService selfModelService;
    private final SelfNarrativeService narrativeService;
    private final CompanionService companionService;
    private final CurrentUser currentUser;

    public SelfController(SelfModelService selfModelService, SelfNarrativeService narrativeService,
                          CompanionService companionService, CurrentUser currentUser) {
        this.selfModelService = selfModelService;
        this.narrativeService = narrativeService;
        this.companionService = companionService;
        this.currentUser = currentUser;
    }

    @GetMapping
    public Map<String, Object> self(@PathVariable String companionId) {
        String userId = currentUser.requireUserId();
        companionService.requireOwned(userId, companionId);
        SelfModel m = selfModelService.get(companionId);
        if (m == null) {
            return Map.of("narrative", "", "facts", java.util.List.of());
        }
        return Map.of(
                "narrative", m.getNarrative() != null ? m.getNarrative() : "",
                "facts", m.getFacts(),
                "preferences", m.getPreferences(),
                "concerns", m.getConcerns(),
                "plans", m.getPlans(),
                "version", m.getVersion()
        );
    }
}
