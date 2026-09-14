package com.luxera.companion.person;

import com.luxera.companion.auth.User;
import com.luxera.companion.auth.UserRepository;
import com.luxera.companion.persona.Companion;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;

/**
 * §五 Person 服务: User/Agent 的 Person 身份层。
 * id 与 users.id / companions.id 一致(零迁移), OTHER 人物独立生成。
 */
@Service
public class PersonService {

    private final PersonRepository repo;
    private final UserRepository userRepository;

    public PersonService(PersonRepository repo, UserRepository userRepository) {
        this.repo = repo;
        this.userRepository = userRepository;
    }

    /** 取/建用户的 Person(幂等) */
    @Transactional
    public Person getOrCreateUser(String userId) {
        return repo.findByUserId(userId).orElseGet(() -> {
            Person p = new Person();
            p.setId(userId);
            p.setPersonType(Person.TYPE_USER);
            p.setUserId(userId);
            String name = "用户";
            try {
                User u = userRepository.findById(userId).orElse(null);
                if (u != null) {
                    name = u.getNickname() != null && !u.getNickname().isBlank()
                            ? u.getNickname() : u.getUsername();
                }
            } catch (Exception ignored) { }
            p.setName(name);
            p.setMetadata(Map.of("source", "user"));
            return repo.save(p);
        });
    }

    /** 取/建伴侣(Agent)的 Person(幂等), id 沿用 companion.id */
    @Transactional
    public Person getOrCreateAgent(Companion companion) {
        return repo.findByCompanionId(companion.getId()).orElseGet(() -> {
            Person p = new Person();
            p.setId(companion.getId());
            p.setPersonType(Person.TYPE_AGENT);
            p.setCompanionId(companion.getId());
            p.setName(companion.getName());
            p.setGender(companion.getGender());
            p.setMetadata(Map.of("source", "companion"));
            return repo.save(p);
        });
    }

    /** 新建 OTHER 人物(数字人的社会关系) */
    @Transactional
    public Person createOther(String name, String gender, Map<String, Object> metadata) {
        Person p = new Person();
        p.setPersonType(Person.TYPE_OTHER);
        p.setName(name);
        p.setGender(gender);
        p.setMetadata(metadata);
        return repo.save(p);
    }

    @Transactional(readOnly = true)
    public Person requireByCompanionId(String companionId) {
        return repo.findByCompanionId(companionId)
                .orElseThrow(() -> new javax.persistence.EntityNotFoundException("伴侣的 Person 不存在"));
    }

    @Transactional(readOnly = true)
    public Person requireByUserId(String userId) {
        return repo.findByUserId(userId)
                .orElseThrow(() -> new javax.persistence.EntityNotFoundException("用户的 Person 不存在"));
    }
}
