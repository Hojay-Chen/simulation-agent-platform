package com.luxera.companion.reflection;

import com.luxera.companion.config.CurrentUser;
import com.luxera.companion.persona.CompanionService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/companions/{companionId}/reflections")
public class ReflectionController {

    private final ReflectionRecordRepository recordRepo;
    private final CompanionService companionService;
    private final CurrentUser currentUser;

    public ReflectionController(ReflectionRecordRepository recordRepo,
                                CompanionService companionService, CurrentUser currentUser) {
        this.recordRepo = recordRepo;
        this.companionService = companionService;
        this.currentUser = currentUser;
    }

    @GetMapping
    public List<ReflectionRecord> list(@PathVariable String companionId) {
        String userId = currentUser.requireUserId();
        companionService.requireOwned(userId, companionId);
        return recordRepo.findByCompanionIdOrderByCreatedAtDesc(companionId);
    }
}
