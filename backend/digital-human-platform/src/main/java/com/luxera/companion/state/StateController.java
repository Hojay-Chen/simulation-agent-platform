package com.luxera.companion.state;

import com.luxera.companion.config.CurrentUser;
import com.luxera.companion.persona.CompanionService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/companions/{companionId}/state")
public class StateController {

    private final AgentStateService agentStateService;
    private final CompanionService companionService;
    private final CurrentUser currentUser;

    public StateController(AgentStateService agentStateService, CompanionService companionService,
                           CurrentUser currentUser) {
        this.agentStateService = agentStateService;
        this.companionService = companionService;
        this.currentUser = currentUser;
    }

    @GetMapping
    public AgentState state(@PathVariable String companionId) {
        String userId = currentUser.requireUserId();
        companionService.requireOwned(userId, companionId);
        return agentStateService.getOrCreate(companionId);
    }
}
