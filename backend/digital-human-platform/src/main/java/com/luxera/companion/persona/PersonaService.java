package com.luxera.companion.persona;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class PersonaService {

    private final PersonaVersionRepository repo;

    public PersonaService(PersonaVersionRepository repo) {
        this.repo = repo;
    }

    public PersonaVersion saveInitial(String companionId, Persona p) {
        PersonaVersion v = new PersonaVersion();
        v.setCompanionId(companionId);
        v.setVersion(1);
        v.setActive(true);
        v.setPersona(p);
        v.setChangeSource("user");
        v.setChangeReason("初始创建");
        return repo.save(v);
    }

    @Transactional
    public PersonaVersion update(String companionId, Persona p, String reason, String changeSource) {
        repo.findByCompanionIdAndActiveTrue(companionId).ifPresent(v -> {
            v.setActive(false);
            repo.save(v);
        });
        PersonaVersion latest = repo.findTopByCompanionIdOrderByVersionDesc(companionId).orElse(null);
        int next = latest == null ? 1 : latest.getVersion() + 1;
        PersonaVersion v = new PersonaVersion();
        v.setCompanionId(companionId);
        v.setVersion(next);
        v.setActive(true);
        v.setPersona(p);
        v.setChangeReason(reason);
        v.setChangeSource(changeSource);
        return repo.save(v);
    }

    public Persona getActive(String companionId) {
        return repo.findByCompanionIdAndActiveTrue(companionId)
                .map(PersonaVersion::getPersona)
                .orElse(null);
    }

    public List<PersonaVersion> history(String companionId) {
        return repo.findByCompanionIdOrderByVersionAsc(companionId);
    }
}
