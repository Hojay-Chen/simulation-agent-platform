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

import java.util.ArrayList;
import java.util.List;

/**
 * §五 一次性回填: 为存量 user/companion 建立 Person 身份层, 并归一化旧关系类型。
 * 幂等: getOrCreate 语义, 每次启动可安全执行。
 *
 * <h2>这个方法<b>不能</b>整体包在一个事务里 —— 否则启动耗时随伴侣表平方增长</h2>
 *
 * 它曾经在 {@code run()} 上挂着 {@code @Transactional}。那看起来是最自然不过的写法
 * ("回填要么全成要么全不成"), 但它把整张 {@code companions} 表连同每个人的 Person
 * 都装进了<b>同一个持久化上下文</b>, 而循环里每一步都要查一次库
 * ({@link PersonService#getOrCreateAgent} 的 {@code findByCompanionId}) —— 每次查询
 * 之前 Hibernate 都要 auto-flush, 而 flush 会遍历上下文里的<b>全部</b>实体做脏检查。
 * 于是 n 次查询 × O(n) 次脏检查 = O(n²)。
 *
 * <p>实测(2026-09 排障): {@code companion_test} 库累积到 4786 个伴侣时, 一次上下文加载
 * 在 {@code AbstractStandardBasicType.isEqual ← DefaultFlushEntityEventListener.checkId}
 * 上烧了 <b>40 分钟</b> CPU 都没出来(那次是测试套件卡死, 顺着线程栈找过来的);
 * 而生产库只有 54 个伴侣, 所以一直没人注意到 —— 15 秒就过去了。
 * 一条"规模一大就变成 40 分钟"的启动路径不该留在平台里: 这个平台本来就以"很多个 agent"
 * 为前提。
 *
 * <p>去掉外层事务之后, 每一次 {@code getOrCreate*} 各自成事务(那两个方法自己就带
 * {@code @Transactional}), 持久化上下文<b>用完即弃</b>, 于是整体回到 O(n)。
 * 代价是"回填一半失败"不再整体回滚 —— 这里有意接受: 这个方法本来就是幂等的
 * ({@code getOrCreate} 语义), 重跑一次即可, 而"一条坏数据把几千条好的回填一起回滚掉"
 * 才是更糟的结果。读到的 {@link Companion} 变成游离态也没关系: 循环只读
 * {@code getId/getName/getGender} 三个列, 不碰任何懒加载关联。
 *
 * <h2>稳态下它应当<b>一个字节都不写</b></h2>
 *
 * 一个每次启动都重写全表的"回填"不是回填。所以关系那一段只 {@code saveAll} <b>真正改过</b>
 * 的那些: 老写法对 <b>524 条全部</b> {@code save} 一遍, 每条都是一次 merge(先 SELECT
 * 再比对), 而在归一化早就做完的今天, 那 524 次读没有换来任何一次写。
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
    public void run(ApplicationArguments args) {
        try {
            int agentCount = 0;
            // 刻意**不**在本方法上开事务 —— 见类注释: 那会让启动耗时变成 O(n²)。
            for (Companion c : companionRepository.findAll()) {
                if (c.getDeletedAt() != null) continue;
                personService.getOrCreateAgent(c);
                if (c.getUserId() != null) {
                    personService.getOrCreateUser(c.getUserId());
                }
                agentCount++;
            }
            int relCount = 0;
            // 只收集**真正改过**的: 稳态下这个列表是空的, 于是 saveAll 一次都不调 —— 见类注释。
            List<Relationship> changed = new ArrayList<>();
            for (Relationship r : relationshipRepository.findAll()) {
                boolean dirty = false;
                if (r.getUserPersonId() == null) {
                    r.setUserPersonId(r.getUserId());
                    dirty = true;
                }
                if (r.getAgentPersonId() == null) {
                    r.setAgentPersonId(r.getCompanionId());
                    dirty = true;
                }
                if (r.getRelationshipType() != null && !RelationshipTypes.isValid(r.getRelationshipType())) {
                    String normalized = RelationshipTypes.normalize(r.getRelationshipType());
                    if (normalized != null && !normalized.equals(r.getRelationshipType())) {
                        r.setRelationshipType(normalized);
                        relCount++;
                        dirty = true;
                    }
                }
                if (dirty) changed.add(r);
            }
            // 一次 saveAll(= 一个事务)而不是每条一次 save: 改过的通常只有几条,
            // 而每条一次 save 会是 n 个事务、n 次 merge。
            if (!changed.isEmpty()) {
                relationshipRepository.saveAll(changed);
            }
            if (agentCount > 0 || relCount > 0) {
                log.info("[Person回填] 伴侣Person={}, 关系归一化={}", agentCount, relCount);
            }
        } catch (Exception e) {
            log.warn("[Person回填] 失败(不影响启动): {}", e.getMessage());
        }
    }
}
