package com.luxera.companion.relationship;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Service
public class RelationshipThreadService {

    private final RelationshipThreadRepository repo;

    public RelationshipThreadService(RelationshipThreadRepository repo) {
        this.repo = repo;
    }

    @Transactional
    public RelationshipThread createOrTouch(String relationshipId, String topic, String summary, double importance) {
        if (topic == null || topic.isBlank()) return null;
        return repo.findTopByRelationshipIdAndTopic(relationshipId, topic)
                .map(existing -> {
                    existing.setLastActivityAt(LocalDateTime.now());
                    existing.setImportance(Math.max(existing.getImportance(), importance));
                    if (summary != null && !summary.isBlank()) existing.setSummary(summary);
                    return repo.save(existing);
                })
                .orElseGet(() -> {
                    RelationshipThread t = new RelationshipThread();
                    t.setRelationshipId(relationshipId);
                    t.setTopic(topic);
                    t.setSummary(summary);
                    t.setImportance(importance);
                    t.setLastActivityAt(LocalDateTime.now());
                    return repo.save(t);
                });
    }

    @Transactional(readOnly = true)
    public List<RelationshipThread> activeThreads(String relationshipId) {
        return repo.findByRelationshipIdAndStatusOrderByLastActivityAtDesc(relationshipId, "ACTIVE");
    }

    @Transactional
    public void close(String threadId) {
        repo.findById(threadId).ifPresent(t -> {
            t.setStatus("CLOSED");
            repo.save(t);
        });
    }
}
