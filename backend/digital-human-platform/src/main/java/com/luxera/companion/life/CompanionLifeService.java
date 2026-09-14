package com.luxera.companion.life;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Service
public class CompanionLifeService {

    private final CompanionLifeRepository repo;

    public CompanionLifeService(CompanionLifeRepository repo) {
        this.repo = repo;
    }

    @Transactional
    public CompanionLife getOrCreate(String companionId) {
        return repo.findByCompanionId(companionId).orElseGet(() -> {
            CompanionLife life = new CompanionLife();
            life.setCompanionId(companionId);
            life.setLifeDate(LocalDate.now());
            life.setLastSimulatedAt(LocalDateTime.now());
            life.setDayPhase("MORNING");
            return repo.save(life);
        });
    }

    @Transactional
    public CompanionLife save(CompanionLife life) {
        return repo.save(life);
    }
}
