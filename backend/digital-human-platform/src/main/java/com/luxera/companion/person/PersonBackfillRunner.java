package com.luxera.companion.person;

import com.luxera.companion.persona.Companion;
import com.luxera.companion.persona.CompanionRepository;
import com.luxera.companion.relationship.Relationship;
import com.luxera.companion.relationship.RelationshipRepository;
import com.luxera.companion.relationship.RelationshipTypes;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * §五 一次性回填: 为存量 user/companion 建立 Person 身份层, 并归一化旧关系类型。
 * 幂等: getOrCreate 语义, 每次启动可安全执行。
 */
@Slf4j
@Component
public class PersonBackfillRunner implements ApplicationRunner {

    private final PersonService personService;
    private final CompanionRepository companionRepository;
    private final RelationshipRepository relationshipRepository;

    public PersonBackfillRunner(PersonService personService, CompanionRepository companionRepository,
                                RelationshipRepository relationshipRepository) {
        this.personService = personService;
        this.companionRepository = companionRepository;
        this.relationshipRepository = relationshipRepository;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        try {
            int agentCount = 0;
            for (Companion c : companionRepository.findAll()) {
                if (c.getDeletedAt() != null) continue;
                personService.getOrCreateAgent(c);
                if (c.getUserId() != null) {
                    personService.getOrCreateUser(c.getUserId());
                }
                agentCount++;
            }
            int relCount = 0;
            for (Relationship r : relationshipRepository.findAll()) {
                if (r.getUserPersonId() == null) {
                    r.setUserPersonId(r.getUserId());
                }
                if (r.getAgentPersonId() == null) {
                    r.setAgentPersonId(r.getCompanionId());
                }
                if (r.getRelationshipType() != null && !RelationshipTypes.isValid(r.getRelationshipType())) {
                    String normalized = RelationshipTypes.normalize(r.getRelationshipType());
                    if (normalized != null && !normalized.equals(r.getRelationshipType())) {
                        r.setRelationshipType(normalized);
                        relCount++;
                    }
                }
                relationshipRepository.save(r);
            }
            if (agentCount > 0 || relCount > 0) {
                log.info("[Person回填] 伴侣Person={}, 关系归一化={}", agentCount, relCount);
            }
        } catch (Exception e) {
            log.warn("[Person回填] 失败(不影响启动): {}", e.getMessage());
        }
    }
}
