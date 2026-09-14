package com.luxera.companion.persona;

import com.luxera.companion.config.CurrentUser;
import com.luxera.companion.relationship.Relationship;
import com.luxera.companion.relationship.RelationshipService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import javax.validation.Valid;
import java.time.LocalDate;
import java.util.List;

@RestController
@RequestMapping("/api/companions")
public class CompanionController {

    private final CompanionService companionService;
    private final RelationshipService relationshipService;
    private final CurrentUser currentUser;

    public CompanionController(CompanionService companionService,
                               RelationshipService relationshipService,
                               CurrentUser currentUser) {
        this.companionService = companionService;
        this.relationshipService = relationshipService;
        this.currentUser = currentUser;
    }

    /** 自然语言 → 编译人格 + 默认场景预览 */
    @PostMapping("/compile")
    public CompanionDtos.CompileResponse compile(@Valid @RequestBody CompanionDtos.CompileRequest req) {
        Persona persona = companionService.compile(req.getDescription());
        String preview = companionService.preview(persona, null);
        CompanionDtos.CompileResponse resp = new CompanionDtos.CompileResponse();
        resp.setPersona(persona);
        resp.setPreview(preview);
        return resp;
    }

    /** 任意场景预览 */
    @PostMapping("/preview")
    public CompanionDtos.PreviewResponse preview(@Valid @RequestBody CompanionDtos.PreviewRequest req) {
        CompanionDtos.PreviewResponse resp = new CompanionDtos.PreviewResponse();
        resp.setResponse(companionService.preview(req.getPersona(), req.getScenario()));
        return resp;
    }

    @GetMapping
    public List<CompanionDtos.CompanionDto> list() {
        String userId = currentUser.requireUserId();
        return companionService.list(userId).stream().map(c -> toDto(userId, c)).toList();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public CompanionDtos.CompanionDto create(@Valid @RequestBody CompanionDtos.CreateRequest req) {
        if (req.getPersona() == null) {
            throw new IllegalArgumentException("persona 不能为空");
        }
        String userId = currentUser.requireUserId();
        Companion c = companionService.create(userId, req.getPersona(), req.getRelationshipType());
        return toDto(userId, c);
    }

    @GetMapping("/{id}")
    public CompanionDtos.CompanionDto get(@PathVariable String id) {
        String userId = currentUser.requireUserId();
        return toDto(userId, companionService.requireOwned(userId, id));
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable String id) {
        companionService.delete(currentUser.requireUserId(), id);
    }

    /** 重新描述 → 编译为新版本人格 */
    @PutMapping("/{id}/persona")
    public PersonaVersion updatePersona(@PathVariable String id,
                                        @RequestBody CompanionDtos.UpdatePersonaRequest req) {
        return companionService.updatePersona(currentUser.requireUserId(), id,
                req.getDescription(), req.getReason());
    }

    @GetMapping("/{id}/life-events")
    public List<CompanionDtos.LifeEventDto> lifeEvents(@PathVariable String id) {
        companionService.requireOwned(currentUser.requireUserId(), id);
        return companionService.listLifeEvents(id).stream()
                .map(e -> {
                    CompanionDtos.LifeEventDto dto = new CompanionDtos.LifeEventDto();
                    dto.setId(e.getId());
                    dto.setType(e.getType());
                    dto.setSubtype(e.getSubtype());
                    dto.setTitle(e.getTitle());
                    dto.setDescription(e.getDescription());
                    dto.setStartTime(e.getStartTime());
                    dto.setEndTime(e.getEndTime());
                    dto.setImportance(e.getImportance());
                    dto.setEmotionalSignificance(e.getEmotionalSignificance());
                    return dto;
                }).toList();
    }

    /** 人格版本历史(含演化记录) */
    @GetMapping("/{id}/persona/versions")
    public List<PersonaVersion> personaVersions(@PathVariable String id) {
        companionService.requireOwned(currentUser.requireUserId(), id);
        return companionService.listPersonaVersions(id);
    }

    private CompanionDtos.CompanionDto toDto(String userId, Companion c) {
        CompanionDtos.CompanionDto dto = new CompanionDtos.CompanionDto();
        dto.setId(c.getId());
        dto.setName(c.getName());
        dto.setGender(c.getGender());
        dto.setAge(c.getBirthDate() != null ? c.age() : null);
        dto.setBirthDate(c.getBirthDate());
        dto.setNextBirthday(c.getBirthDate() != null ? nextBirthday(c.getBirthDate()) : null);
        dto.setBirthPlace(c.getBirthPlace());
        dto.setNationality(c.getNationality());
        dto.setTimezone(c.getTimezone());
        dto.setGreeting(c.getGreeting());
        dto.setPersona(companionService.getPersona(c.getId()));
        dto.setCreatedAt(c.getCreatedAt());
        Relationship rel = relationshipService.find(userId, c.getId());
        if (rel != null) {
            dto.setRelationshipType(rel.getRelationshipType());
            dto.setRelationshipStage(rel.getRelationshipStage());
        }
        return dto;
    }

    private static LocalDate nextBirthday(LocalDate birth) {
        LocalDate today = LocalDate.now();
        LocalDate next = birth.withYear(today.getYear());
        if (!next.isAfter(today)) {
            next = next.plusYears(1);
        }
        return next;
    }
}
