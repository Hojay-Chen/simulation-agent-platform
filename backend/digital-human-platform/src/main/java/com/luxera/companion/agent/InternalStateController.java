package com.luxera.companion.agent;

import com.luxera.companion.config.CurrentUser;
import com.luxera.companion.experience.Experience;
import com.luxera.companion.experience.ExperienceService;
import com.luxera.companion.openloop.OpenLoop;
import com.luxera.companion.openloop.OpenLoopService;
import com.luxera.companion.persona.CompanionService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 内部状态读取(用户视角, 不暴露数值面板, 不暴露内部想法)。
 * 设计文档 §30/§31: 用户看到"她最近怎么样/未完成的事/经历", 而不是 mood=0.63 或 Internal Thought。
 */
@RestController
@RequestMapping("/api/companions/{companionId}")
public class InternalStateController {

    private final OpenLoopService openLoopService;
    private final ExperienceService experienceService;
    private final CompanionService companionService;
    private final CurrentUser currentUser;

    public InternalStateController(OpenLoopService openLoopService,
                                   ExperienceService experienceService,
                                   CompanionService companionService,
                                   CurrentUser currentUser) {
        this.openLoopService = openLoopService;
        this.experienceService = experienceService;
        this.companionService = companionService;
        this.currentUser = currentUser;
    }

    /** 未完成事项(用户可查看/关闭) */
    @GetMapping("/open-loops")
    public List<OpenLoop> openLoops(@PathVariable String companionId) {
        String userId = currentUser.requireUserId();
        companionService.requireOwned(userId, companionId);
        return openLoopService.activeLoops(companionId);
    }

    /** 最近经历(用户视角) */
    @GetMapping("/experiences")
    public List<Experience> experiences(@PathVariable String companionId) {
        String userId = currentUser.requireUserId();
        companionService.requireOwned(userId, companionId);
        return experienceService.list(companionId);
    }
}
