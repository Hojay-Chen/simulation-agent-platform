package com.luxera.companion.person;

import com.luxera.companion.persona.Companion;
import com.luxera.companion.persona.CompanionRepository;
import com.luxera.companion.relationship.Relationship;
import com.luxera.companion.relationship.RelationshipRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link PersonBackfillRunner} —— <b>一条每次启动都跑的路径, 它的代价必须与"要补的东西"
 * 成正比, 而不是与"库里有东西"成正比</b>。
 *
 * <h2>这个测试是为一次 40 分钟的卡死写的</h2>
 *
 * 2026-09 排障: 测试套件跑到某个 Spring 上下文加载时整条卡住, 线程栈停在
 * {@code PersonBackfillRunner.run → PersonService.getOrCreateAgent → findByCompanionId}
 * 上, 在 {@code DefaultFlushEntityEventListener.checkId} 里烧 CPU。根因是 {@code run()}
 * 当时挂着 {@code @Transactional}: 整张 {@code companions} 表与所有人的 Person 都进了
 * <b>同一个持久化上下文</b>, 而循环里每查一次库之前 Hibernate 都要 auto-flush, flush 又要
 * 遍历上下文里的<b>全部</b>实体做脏检查 —— n 次查询 × O(n) 次脏检查。
 *
 * <p>生产库只有 54 个伴侣, 所以这条路径在生产上一直是"15 秒启动"里看不出来的一段;
 * {@code companion_test} 累积到 4786 个伴侣时它才现形。两件事都记在这里, 是因为下面
 * 第一条断言防的正是"有人觉得'回填就该包一个事务'再把它加回去" —— 那种改法在 54 个伴侣
 * 的库上<b>完全正常</b>, 要到几千个 agent 时才变成几十分钟, 而那时没人会想到是这里。
 *
 * <h2>第二条断言: 稳态下一个字节都不写</h2>
 *
 * 归一化早就做完的今天, 关系那一段不该有任何写入。老写法对<b>每一条</b>关系都
 * {@code save}(524 条 = 524 次 merge, 每次先 SELECT), 却没换来一次真正的写。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PersonBackfillRunnerTest {

    @Mock
    private PersonService personService;
    @Mock
    private CompanionRepository companionRepository;
    @Mock
    private RelationshipRepository relationshipRepository;
    @InjectMocks
    private PersonBackfillRunner runner;

    private static Companion companion(String id, String userId) {
        Companion c = new Companion();
        c.setId(id);
        c.setName("伴侣-" + id);
        c.setUserId(userId);
        return c;
    }

    private static Relationship relationship(String id, String type) {
        Relationship r = new Relationship();
        r.setId(id);
        r.setUserId("u-" + id);
        r.setCompanionId("c-" + id);
        r.setUserPersonId("u-" + id);
        r.setAgentPersonId("c-" + id);
        r.setRelationshipType(type);
        return r;
    }

    /** 跑一次回填(不关心返回值, 它本来也没有) */
    private void run() {
        runner.run(null);
    }

    @Nested
    @DisplayName("启动代价: 不许把整张表装进一个持久化上下文")
    class StartupCost {

        @Test
        @DisplayName("★ run() 不带 @Transactional —— 加了就会变成 O(n²), 而小库上看不出来")
        void runIsNotTransactional() throws Exception {
            assertNull(PersonBackfillRunner.class
                            .getMethod("run", org.springframework.boot.ApplicationArguments.class)
                            .getAnnotation(Transactional.class),
                    "run() 一旦包上事务, 每次 getOrCreate* 的查询都会触发一次遍历整个上下文"
                            + "的 auto-flush; 伴侣表一大, 启动就变成几十分钟。"
                            + "每一条的幂等性由 PersonService 自己的 @Transactional 保证。");
        }

        @Test
        @DisplayName("逐条调用 personService, 而不是把整表交给它一次处理")
        void callsServicePerCompanion() {
            when(companionRepository.findAll()).thenReturn(List.of(
                    companion("c1", "u1"), companion("c2", "u2")));
            when(relationshipRepository.findAll()).thenReturn(List.of());

            run();

            // 一次一个: 每次调用各自成事务, 上下文用完即弃
            verify(personService, times(2)).getOrCreateAgent(any(Companion.class));
            verify(personService, times(2)).getOrCreateUser(any());
        }
    }

    @Nested
    @DisplayName("回填语义")
    class BackfillSemantics {

        @Test
        @DisplayName("已删除的伴侣不建 Person —— 软删的 agent 不该在身份层复活")
        void skipsDeletedCompanions() {
            Companion deleted = companion("c-del", "u1");
            deleted.setDeletedAt(LocalDateTime.now());
            when(companionRepository.findAll()).thenReturn(List.of(deleted, companion("c-live", "u2")));
            when(relationshipRepository.findAll()).thenReturn(List.of());

            run();

            verify(personService, times(1)).getOrCreateAgent(any(Companion.class));
            verify(personService, never()).getOrCreateAgent(deleted);
        }

        @Test
        @DisplayName("伴侣没有 userId 时不造用户 Person —— 不能凭空编一个账号出来")
        void skipsUserWhenNoUserId() {
            when(companionRepository.findAll()).thenReturn(List.of(companion("c1", null)));
            when(relationshipRepository.findAll()).thenReturn(List.of());

            run();

            verify(personService, times(1)).getOrCreateAgent(any(Companion.class));
            verify(personService, never()).getOrCreateUser(any());
        }

        @Test
        @DisplayName("规范化后没有任何变化 → 一个字节都不写(saveAll 一次都不调)")
        void steadyStateWritesNothing() {
            when(companionRepository.findAll()).thenReturn(List.of());
            when(relationshipRepository.findAll()).thenReturn(List.of(
                    relationship("r1", "friend"), relationship("r2", "partner")));

            run();

            verify(relationshipRepository, never()).saveAll(anyList());
            verify(relationshipRepository, never()).save(any());
        }

        @Test
        @DisplayName("只有真正改过的那几条被写回, 而且是一次 saveAll")
        void savesOnlyChangedRelationships() {
            Relationship needsFix = relationship("r-fix", "girlfriend"); // 旧类型, 归一化后是 lover
            when(companionRepository.findAll()).thenReturn(List.of());
            when(relationshipRepository.findAll()).thenReturn(List.of(
                    relationship("r-ok", "friend"), needsFix));

            run();

            @SuppressWarnings("unchecked")
            ArgumentCaptor<List<Relationship>> captor = ArgumentCaptor.forClass(List.class);
            verify(relationshipRepository, times(1)).saveAll(captor.capture());
            assertEquals(1, captor.getValue().size(), "只该写回那条真的需要归一化的");
            assertEquals("r-fix", captor.getValue().get(0).getId());
            assertEquals("lover", needsFix.getRelationshipType(), "归一化的结果要落在这一行上");
        }

        @Test
        @DisplayName("缺 personId 的关系会被补上并写回 —— 这是回填的本来目的")
        void backfillsMissingPersonIds() {
            Relationship r = relationship("r1", "friend");
            r.setUserPersonId(null);
            r.setAgentPersonId(null);
            when(companionRepository.findAll()).thenReturn(List.of());
            when(relationshipRepository.findAll()).thenReturn(List.of(r));

            run();

            verify(relationshipRepository, times(1)).saveAll(anyList());
            assertEquals("u-r1", r.getUserPersonId());
            assertEquals("c-r1", r.getAgentPersonId());
        }
    }

    @Nested
    @DisplayName("不影响启动")
    class NeverBlocksStartup {

        @Test
        @DisplayName("回填炸了也不许把启动带下去 —— 它是加分项, 不是启动前置条件")
        void swallowsFailures() {
            when(companionRepository.findAll()).thenThrow(new RuntimeException("库连不上"));

            run(); // 不抛 = 通过

            verify(personService, never()).getOrCreateAgent(any(Companion.class));
        }

        @Test
        @DisplayName("关系那一段炸了同样不抛")
        void swallowsRelationshipFailures() {
            when(companionRepository.findAll()).thenReturn(List.of());
            when(relationshipRepository.findAll()).thenThrow(new RuntimeException("库连不上"));

            run();
        }
    }

    @Nested
    @DisplayName("幂等: 跑第二遍不产生额外效果")
    class Idempotent {

        @Test
        @DisplayName("★ 连续跑两次, 第二次一条写都没有 —— 这就是'每次启动可安全执行'的意思")
        void secondRunIsSilent() {
            when(companionRepository.findAll()).thenReturn(List.of(companion("c1", "u1")));
            when(relationshipRepository.findAll()).thenReturn(List.of());

            run();
            run();

            // Person 那边两次都调 getOrCreate(它自己幂等); 关系这边始终没有写入 ——
            // 第二次跑与第一次跑一样安静, 就是"每次启动可安全执行"的全部含义
            verify(relationshipRepository, never()).saveAll(anyList());
            verify(relationshipRepository, never()).save(any());
        }
    }
}
