package com.luxera.companion.relationship;

import com.luxera.companion.config.CurrentUser;
import com.luxera.companion.persona.CompanionService;
import com.luxera.companion.state.AgentState;
import com.luxera.companion.state.AgentStateService;
import lombok.Data;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/companions/{companionId}/relationship")
public class RelationshipController {

    private final RelationshipService relationshipService;
    private final RelationshipEventRepository eventRepo;
    private final SharedExperienceRepository sharedRepo;
    private final RelationshipThreadService threadService;
    private final RelationshipNarrativeService narrativeService;
    private final PromiseService promiseService;
    private final AgentStateService agentStateService;
    private final CompanionService companionService;
    private final CurrentUser currentUser;

    public RelationshipController(RelationshipService relationshipService,
                                  RelationshipEventRepository eventRepo,
                                  SharedExperienceRepository sharedRepo,
                                  RelationshipThreadService threadService,
                                  RelationshipNarrativeService narrativeService,
                                  PromiseService promiseService,
                                  AgentStateService agentStateService,
                                  CompanionService companionService,
                                  CurrentUser currentUser) {
        this.relationshipService = relationshipService;
        this.eventRepo = eventRepo;
        this.sharedRepo = sharedRepo;
        this.threadService = threadService;
        this.narrativeService = narrativeService;
        this.promiseService = promiseService;
        this.agentStateService = agentStateService;
        this.companionService = companionService;
        this.currentUser = currentUser;
    }

    @GetMapping
    public Map<String, Object> summary(@PathVariable String companionId) {
        String userId = currentUser.requireUserId();
        companionService.requireOwned(userId, companionId);
        Relationship r = relationshipService.require(userId, companionId);
        AgentState state = agentStateService.get(companionId);
        return Map.of(
                "relationship", r,
                "events", eventRepo.findByRelationshipIdOrderByOccurredAtAsc(r.getId()),
                "sharedExperiences", sharedRepo.findByRelationshipIdOrderByOccurredAtDesc(r.getId()),
                "state", state
        );
    }

    @GetMapping("/events")
    public List<RelationshipEvent> events(@PathVariable String companionId) {
        String userId = currentUser.requireUserId();
        companionService.requireOwned(userId, companionId);
        Relationship r = relationshipService.require(userId, companionId);
        return eventRepo.findByRelationshipIdOrderByOccurredAtAsc(r.getId());
    }

    @GetMapping("/shared-experiences")
    public List<SharedExperience> sharedExperiences(@PathVariable String companionId) {
        String userId = currentUser.requireUserId();
        companionService.requireOwned(userId, companionId);
        Relationship r = relationshipService.require(userId, companionId);
        return sharedRepo.findByRelationshipIdOrderByOccurredAtDesc(r.getId());
    }

    // ── Relationship 2.0 ───────────────────

    @GetMapping("/threads")
    public List<RelationshipThread> threads(@PathVariable String companionId) {
        String userId = currentUser.requireUserId();
        companionService.requireOwned(userId, companionId);
        Relationship r = relationshipService.require(userId, companionId);
        return threadService.activeThreads(r.getId());
    }

    @GetMapping("/narrative")
    public RelationshipNarrative narrative(@PathVariable String companionId) {
        String userId = currentUser.requireUserId();
        companionService.requireOwned(userId, companionId);
        Relationship r = relationshipService.require(userId, companionId);
        return narrativeService.getOrCreate(r.getId());
    }

    @GetMapping("/promises")
    public List<Promise> promises(@PathVariable String companionId) {
        String userId = currentUser.requireUserId();
        companionService.requireOwned(userId, companionId);
        Relationship r = relationshipService.require(userId, companionId);
        return promiseService.openPromises(r.getId());
    }

    /** §七: 用户可调整关系类型 —— 关系是真实状态, 可以改变 */
    @PutMapping("/type")
    public Relationship changeType(@PathVariable String companionId,
                                   @RequestBody ChangeTypeRequest req) {
        String userId = currentUser.requireUserId();
        companionService.requireOwned(userId, companionId);
        return relationshipService.changeType(userId, companionId, req.getType());
    }

    @Data
    public static class ChangeTypeRequest {
        private String type;
    }
}
