package com.luxera.companion.relationship;

import com.luxera.companion.person.Person;
import com.luxera.companion.person.PersonRepository;
import com.luxera.companion.person.PersonService;
import com.luxera.companion.persona.Companion;
import com.luxera.companion.persona.CompanionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDateTime;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * §五~§八 Person + Relationship Graph 测试:
 * 1. 用户/Agent 都是 Person(id 与 user.id/companion.id 一致)
 * 2. 创建伴侣按关系类型初始化多维关系(恋人 vs 同事差异显著)
 * 3. 沉默使联系压力上升(关系维护需求 → 驱动主动联系)
 * 4. 关系类型可调整
 */
@ActiveProfiles("test")
@SpringBootTest
class RelationshipGraphTest {

    @Autowired
    PersonService personService;
    @Autowired
    PersonRepository personRepository;
    @Autowired
    CompanionRepository companionRepository;
    @Autowired
    RelationshipService relationshipService;
    @Autowired
    RelationshipRepository relationshipRepository;

    private String companionId;
    private String userId = "rel-user";

    @BeforeEach
    void setUp() {
        companionId = UUID.randomUUID().toString();
        Companion c = new Companion();
        c.setId(companionId);
        c.setUserId(userId);
        c.setName("林夏");
        c.setGender("female");
        companionRepository.save(c);
    }

    @Test
    void userAndAgentArePersons() {
        Person user = personService.getOrCreateUser(userId);
        Person agent = personService.getOrCreateAgent(companionRepository.findById(companionId).orElseThrow());

        assertEquals(userId, user.getId(), "USER person id 沿用 user.id");
        assertEquals(Person.TYPE_USER, user.getPersonType());
        assertEquals(companionId, agent.getId(), "AGENT person id 沿用 companion.id");
        assertEquals(Person.TYPE_AGENT, agent.getPersonType());
        assertEquals("林夏", agent.getName());

        // 幂等
        assertEquals(user.getId(), personService.getOrCreateUser(userId).getId());
    }

    @Test
    void relationshipInitializedByType() {
        Relationship lover = new Relationship();
        lover.setUserId(userId);
        lover.setCompanionId(companionId);
        relationshipService.initByType(lover, RelationshipTypes.LOVER);

        // 同事关系用另一个伴侣(避免 (user, companion) 唯一约束冲突)
        String colleagueId = UUID.randomUUID().toString();
        Companion colleague = new Companion();
        colleague.setId(colleagueId);
        colleague.setUserId(userId);
        colleague.setName("同事甲");
        companionRepository.save(colleague);
        Relationship colleagueRel = new Relationship();
        colleagueRel.setUserId(userId);
        colleagueRel.setCompanionId(colleagueId);
        relationshipService.initByType(colleagueRel, RelationshipTypes.COLLEAGUE);

        // 恋人与同事在亲密维度上差异显著
        assertTrue(lover.getIntimacy() > colleagueRel.getIntimacy() + 0.3, "恋人亲密应显著高于同事");
        assertTrue(lover.getAffection() > colleagueRel.getAffection() + 0.4, "恋人好感应显著高于同事");
        assertEquals(RelationshipTypes.LOVER, lover.getRelationshipType());
        assertTrue(lover.getTension() <= 0.2, "初始张力低");
    }

    @Test
    void silenceRaisesConnectionPressure() {
        Relationship r = new Relationship();
        r.setUserId(userId);
        r.setCompanionId(companionId);
        r.setLastInteractionAt(LocalDateTime.now().minusHours(50));
        relationshipService.initByType(r, RelationshipTypes.LOVER);
        relationshipRepository.save(r);

        relationshipService.decayConnectionPressure(userId, companionId, LocalDateTime.now());
        Relationship after = relationshipService.require(userId, companionId);
        assertTrue(after.getConnectionPressure() > 0.1,
                "沉默 50h 后联系压力应上升, 实际 " + after.getConnectionPressure());
    }

    @Test
    void relationshipTypeCanChange() {
        Relationship r = new Relationship();
        r.setUserId(userId);
        r.setCompanionId(companionId);
        relationshipService.initByType(r, RelationshipTypes.FRIEND);
        relationshipRepository.save(r);

        Relationship changed = relationshipService.changeType(userId, companionId, RelationshipTypes.BEST_FRIEND);
        assertEquals(RelationshipTypes.BEST_FRIEND, changed.getRelationshipType());
        assertTrue(changed.getIntimacy() > 0.3, "成为好朋友后亲密应高于普通朋友初始值");
    }
}
