package com.luxera.companion.relationship;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class PromiseService {

    private static final Pattern COMPANION_PROMISE = Pattern.compile("(?:答应|答应过|承诺)(你|给)(?:的|要)?([^。！？!?]{2,40})");
    private static final Pattern USER_PROMISE = Pattern.compile("(?:我答应|我承诺|答应你)(?:的|要)?([^。！？!?]{2,40})");

    private final PromiseRepository repo;

    public PromiseService(PromiseRepository repo) {
        this.repo = repo;
    }

    @Transactional
    public Promise create(String relationshipId, String promisor, String promiseText, LocalDateTime dueAt) {
        if (promiseText == null || promiseText.isBlank()) return null;
        Promise p = new Promise();
        p.setRelationshipId(relationshipId);
        p.setPromisor(promisor);
        p.setPromiseText(promiseText);
        p.setDueAt(dueAt);
        return repo.save(p);
    }

    /** 从对话识别承诺(设计文档 §10.2) */
    @Transactional
    public Promise maybeExtractFromText(String relationshipId, String userText) {
        if (userText == null) return null;
        Matcher companion = COMPANION_PROMISE.matcher(userText);
        if (companion.find()) {
            return create(relationshipId, "COMPANION", companion.group(2), null);
        }
        Matcher user = USER_PROMISE.matcher(userText);
        if (user.find()) {
            return create(relationshipId, "USER", user.group(1), null);
        }
        return null;
    }

    @Transactional
    public void resolve(String promiseId, String status) {
        repo.findById(promiseId).ifPresent(p -> {
            p.setStatus(status);
            p.setResolvedAt(LocalDateTime.now());
            repo.save(p);
        });
    }

    @Transactional(readOnly = true)
    public List<Promise> openPromises(String relationshipId) {
        return repo.findByRelationshipIdAndStatusOrderByCreatedAtDesc(relationshipId, "OPEN");
    }
}
