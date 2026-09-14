package com.luxera.companion.usermodel;

import com.luxera.companion.config.CurrentUser;
import com.luxera.companion.persona.CompanionService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/companions/{companionId}/user-model")
public class UserModelController {

    private final UserModelService userModelService;
    private final CompanionService companionService;
    private final CurrentUser currentUser;

    public UserModelController(UserModelService userModelService, CompanionService companionService,
                               CurrentUser currentUser) {
        this.userModelService = userModelService;
        this.companionService = companionService;
        this.currentUser = currentUser;
    }

    @GetMapping("/facts")
    public List<UserFact> facts(@PathVariable String companionId) {
        String userId = currentUser.requireUserId();
        companionService.requireOwned(userId, companionId);
        return userModelService.listFacts(userId, companionId);
    }

    @GetMapping("/preferences")
    public List<UserPreference> preferences(@PathVariable String companionId) {
        String userId = currentUser.requireUserId();
        companionService.requireOwned(userId, companionId);
        return userModelService.listPreferences(userId, companionId);
    }

    @GetMapping("/patterns")
    public List<UserPattern> patterns(@PathVariable String companionId) {
        String userId = currentUser.requireUserId();
        companionService.requireOwned(userId, companionId);
        return userModelService.listPatterns(userId, companionId);
    }

    @GetMapping("/hypotheses")
    public List<UserHypothesis> hypotheses(@PathVariable String companionId) {
        String userId = currentUser.requireUserId();
        companionService.requireOwned(userId, companionId);
        return userModelService.listHypotheses(userId, companionId);
    }

    @DeleteMapping("/clear")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void clear(@PathVariable String companionId) {
        String userId = currentUser.requireUserId();
        companionService.requireOwned(userId, companionId);
        userModelService.clearAll(userId, companionId);
    }
}
