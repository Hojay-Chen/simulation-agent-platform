package com.luxera.companion.openloop;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Service
public class OpenLoopService {

    private final OpenLoopRepository repo;

    public OpenLoopService(OpenLoopRepository repo) {
        this.repo = repo;
    }

    @Transactional
    public OpenLoop create(String companionId, String ownerType, String title, String description,
                           double importance, double emotionalWeight, LocalDateTime expectedResolutionAt) {
        if (title == null || title.isBlank()) return null;
        // 去重: 同标题 OPEN/WAITING 只更新时间
        Optional<OpenLoop> existing = repo.findByCompanionIdAndStatusInOrderByImportanceDesc(
                        companionId, List.of("OPEN", "WAITING")).stream()
                .filter(o -> title.equals(o.getTitle()))
                .findFirst();
        if (existing.isPresent()) {
            OpenLoop e = existing.get();
            e.setLastReferencedAt(LocalDateTime.now());
            if (expectedResolutionAt != null) e.setExpectedResolutionAt(expectedResolutionAt);
            return repo.save(e);
        }
        OpenLoop loop = new OpenLoop();
        loop.setCompanionId(companionId);
        loop.setOwnerType(ownerType == null ? "USER_EVENT" : ownerType);
        loop.setTitle(title);
        loop.setDescription(description);
        loop.setImportance(clamp(importance));
        loop.setEmotionalWeight(clamp(emotionalWeight));
        loop.setExpectedResolutionAt(expectedResolutionAt);
        loop.setStatus("OPEN");
        loop.setLastReferencedAt(LocalDateTime.now());
        return repo.save(loop);
    }

    @Transactional(readOnly = true)
    public List<OpenLoop> activeLoops(String companionId) {
        return repo.findByCompanionIdAndStatusInOrderByImportanceDesc(companionId, List.of("OPEN", "WAITING"));
    }

    @Transactional(readOnly = true)
    public java.util.Optional<OpenLoop> getById(String id) {
        return id == null ? java.util.Optional.empty() : repo.findById(id);
    }

    @Transactional
    public void resolve(String loopId) {
        repo.findById(loopId).ifPresent(l -> {
            l.setStatus("RESOLVED");
            repo.save(l);
        });
    }

    @Transactional
    public void abandon(String loopId) {
        repo.findById(loopId).ifPresent(l -> {
            l.setStatus("ABANDONED");
            repo.save(l);
        });
    }

    private static double clamp(double v) {
        return Math.max(0, Math.min(1, v));
    }
}
