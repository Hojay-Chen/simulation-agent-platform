package com.luxera.companion.persistence;

import com.luxera.companion.persistence.entity.ActivityRecord;
import com.luxera.companion.persistence.entity.ContinuousEffectRecord;
import com.luxera.companion.persistence.entity.PlanConstraintRecord;
import com.luxera.companion.persistence.entity.PlanItemRecord;
import com.luxera.companion.persistence.entity.PlanRevisionRecord;
import com.luxera.companion.persistence.entity.WorldEventRecord;
import com.luxera.companion.persistence.entity.WorldObjectRecord;
import com.luxera.companion.persistence.repository.ActivityRecordRepository;
import com.luxera.companion.persistence.repository.ContinuousEffectRecordRepository;
import com.luxera.companion.persistence.repository.PlanConstraintRecordRepository;
import com.luxera.companion.persistence.repository.PlanItemRecordRepository;
import com.luxera.companion.persistence.repository.PlanRevisionRecordRepository;
import com.luxera.companion.persistence.repository.WorldEventRecordRepository;
import com.luxera.companion.persistence.repository.WorldObjectRecordRepository;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 一组<b>内存里的仓库替身</b> —— 让往返测试能跑完"写进库、再从库里读回来"这条路,
 * 而不需要任何数据库。
 *
 * <h2>为什么不换成 H2</h2>
 * 三条理由, 一条比一条硬:
 * <ol>
 *   <li>本仓的测试依赖里<b>没有 H2</b>（{@code build.gradle} 只有
 *       {@code spring-boot-starter-test} 与 {@code postgresql}）。{@code --offline}
 *       解析不到它, 加依赖就是改构建文件 —— 而这次改动不该动构建;</li>
 *   <li>这里被测的东西<b>不是 SQL</b>, 而是"对象 ⇄ 一行"的那段编解码。
 *       真库能证明的额外那一部分（列宽、约束、索引）在这里证明不了,
 *       而那部分由实体上的 {@code @Column} 声明与 §7.2 的对照负责;</li>
 *   <li>H2 与 PG 在 JSON 列、时间精度、排序规则上都不同 —— 用 H2 通过了的测试,
 *       在真机上仍然可能失败, 而它会给人一种"已经验证过了"的错觉。</li>
 * </ol>
 *
 * <h2>替身模拟了真库的三件事, 而这三件事是测试能成立的前提</h2>
 * <ol>
 *   <li><b>{@code @PrePersist} 生成主键。</b> {@code save} 在没有 id 时发一个
 *       {@code "xxx-N"} 形状的 id —— 与实体的 {@code assignId()} 做的事一样。
 *       不模拟这一点的后果是 {@code StimulusReplayStore.markDelivered}
 *       那类"按 id 回写"的路径会静默地什么都不做, 而测试却绿;</li>
 *   <li><b>同一主键的二次 {@code save} 覆盖而不是追加。</b> {@code Map.put} 就是
 *       这个语义, 而它是"快照落库"与"标记已投递"两条路径的正确性基础 ——
 *       若替身是追加的, 那么"第二次落库会插入重复行"这个 bug 永远测不出来;</li>
 *   <li><b>派生查询按声明的方法名排序。</b> 每条 {@code findBy...OrderBy...} 都在
 *       这里手写一遍排序, 因为排序<b>是被测语义的一部分</b>
 *       （"按 sequence 正序恢复"、"按 revision_number 倒序取最新"）。
 *       让替身依赖 HashMap 的遍历顺序, 等于让测试偶尔绿、偶尔红。</li>
 * </ol>
 *
 * <h2>为什么不用 {@code MockitoExtension}</h2>
 * 那个扩展默认是 STRICT_STUBS: 一个"这个用例没走到"的桩会让测试报
 * {@code UnnecessaryStubbingException}。而这里的替身是<b>按整张表</b>配好的
 * （一次配齐, 给同一个文件里的多个用例共用）, 于是严格的桩检查会把
 * "这个替身配全了"误判成"这个桩没用到"。用裸 {@code mock()} 明确放弃那项检查,
 * 而不是在每个桩上加 {@code lenient()} —— 后者会把这份文件变成一屏注解。
 */
final class FakeRepositories {

    // ─────────────────────────── world_event ───────────────────────────

    static WorldEventRecordRepository worldEvents() {
        Map<String, WorldEventRecord> rows = new LinkedHashMap<>();
        int[] seq = {0};
        WorldEventRecordRepository repo = mock(WorldEventRecordRepository.class);

        when(repo.save(any(WorldEventRecord.class))).thenAnswer(inv -> {
            WorldEventRecord row = inv.getArgument(0);
            assignId(row::getId, row::setId, "evt", seq);
            rows.put(row.getId(), row);
            return row;
        });
        when(repo.saveAll(anyList())).thenAnswer(inv -> {
            List<WorldEventRecord> all = inv.getArgument(0);
            for (WorldEventRecord row : all) {
                assignId(row::getId, row::setId, "evt", seq);
                rows.put(row.getId(), row);
            }
            return all;
        });
        when(repo.findByWorldIdAndPublishedAtIsNullOrderByOccurredAtAsc(anyString())).thenAnswer(inv -> {
            String worldId = inv.getArgument(0);
            return rows.values().stream()
                    .filter(r -> Objects.equals(worldId, r.getWorldId()))
                    .filter(r -> r.getPublishedAt() == null)
                    .sorted(Comparator.comparing(WorldEventRecord::getOccurredAt))
                    .collect(Collectors.toList());
        });
        when(repo.findAllById(anyList())).thenAnswer(inv -> {
            List<String> ids = inv.getArgument(0);
            List<WorldEventRecord> found = new ArrayList<>();
            for (String id : ids) {
                WorldEventRecord row = rows.get(id);
                if (row != null) {
                    found.add(row);
                }
            }
            return found;
        });
        return repo;
    }

    // ─────────────────────────── continuous_effect ───────────────────────────

    static ContinuousEffectRecordRepository continuousEffects() {
        Map<String, ContinuousEffectRecord> rows = new LinkedHashMap<>();
        ContinuousEffectRecordRepository repo = mock(ContinuousEffectRecordRepository.class);

        when(repo.saveAll(anyList())).thenAnswer(inv -> {
            List<ContinuousEffectRecord> all = inv.getArgument(0);
            for (ContinuousEffectRecord row : all) {
                rows.put(row.getId(), row);
            }
            return all;
        });
        when(repo.save(any(ContinuousEffectRecord.class))).thenAnswer(inv -> {
            ContinuousEffectRecord row = inv.getArgument(0);
            rows.put(row.getId(), row);
            return row;
        });
        when(repo.findByHumanIdOrderBySequenceAsc(anyString())).thenAnswer(inv -> {
            String humanId = inv.getArgument(0);
            return rows.values().stream()
                    .filter(r -> Objects.equals(humanId, r.getHumanId()))
                    .sorted(Comparator.comparingLong(ContinuousEffectRecord::getSequence))
                    .collect(Collectors.toList());
        });
        return repo;
    }

    // ─────────────────────────── activity_record ───────────────────────────

    static ActivityRecordRepository activities() {
        Map<String, ActivityRecord> rows = new LinkedHashMap<>();
        ActivityRecordRepository repo = mock(ActivityRecordRepository.class);

        when(repo.save(any(ActivityRecord.class))).thenAnswer(inv -> {
            ActivityRecord row = inv.getArgument(0);
            rows.put(row.getId(), row);
            return row;
        });
        when(repo.saveAll(anyList())).thenAnswer(inv -> {
            List<ActivityRecord> all = inv.getArgument(0);
            for (ActivityRecord row : all) {
                rows.put(row.getId(), row);
            }
            return all;
        });
        when(repo.findFirstByHumanIdAndStateOrderByStartedAtDesc(anyString(), anyString()))
                .thenAnswer(inv -> {
                    String humanId = inv.getArgument(0);
                    String state = inv.getArgument(1);
                    return rows.values().stream()
                            .filter(r -> Objects.equals(humanId, r.getHumanId()))
                            .filter(r -> Objects.equals(state, r.getState()))
                            .max(Comparator.comparing(ActivityRecord::getStartedAt));
                });
        when(repo.findByHumanIdAndStartedAtBetweenOrderByStartedAtAsc(anyString(), any(Instant.class),
                any(Instant.class))).thenAnswer(inv -> {
            String humanId = inv.getArgument(0);
            Instant from = inv.getArgument(1);
            Instant to = inv.getArgument(2);
            return rows.values().stream()
                    .filter(r -> Objects.equals(humanId, r.getHumanId()))
                    .filter(r -> !r.getStartedAt().isBefore(from) && !r.getStartedAt().isAfter(to))
                    .sorted(Comparator.comparing(ActivityRecord::getStartedAt))
                    .collect(Collectors.toList());
        });
        when(repo.findByPlanItemIdOrderByStartedAtAsc(anyString())).thenAnswer(inv -> {
            String planItemId = inv.getArgument(0);
            return rows.values().stream()
                    .filter(r -> Objects.equals(planItemId, r.getPlanItemId()))
                    .sorted(Comparator.comparing(ActivityRecord::getStartedAt))
                    .collect(Collectors.toList());
        });
        return repo;
    }

    // ─────────────────────────── plan_revision / plan_item / plan_constraint ───────────────────────────

    static PlanRevisionRecordRepository planRevisions() {
        Map<String, PlanRevisionRecord> rows = new LinkedHashMap<>();
        PlanRevisionRecordRepository repo = mock(PlanRevisionRecordRepository.class);

        when(repo.save(any(PlanRevisionRecord.class))).thenAnswer(inv -> {
            PlanRevisionRecord row = inv.getArgument(0);
            rows.put(row.getRevisionId(), row);
            return row;
        });
        when(repo.existsByRevisionId(anyString())).thenAnswer(inv -> rows.containsKey(inv.getArgument(0)));
        when(repo.findById(anyString())).thenAnswer(inv -> Optional.ofNullable(rows.get(inv.getArgument(0))));
        when(repo.findFirstByHumanIdOrderByRevisionNumberDesc(anyString())).thenAnswer(inv -> {
            String humanId = inv.getArgument(0);
            return rows.values().stream()
                    .filter(r -> Objects.equals(humanId, r.getHumanId()))
                    .max(Comparator.comparingLong(PlanRevisionRecord::getRevisionNumber));
        });
        when(repo.findByHumanIdOrderByRevisionNumberAsc(anyString())).thenAnswer(inv -> {
            String humanId = inv.getArgument(0);
            return rows.values().stream()
                    .filter(r -> Objects.equals(humanId, r.getHumanId()))
                    .sorted(Comparator.comparingLong(PlanRevisionRecord::getRevisionNumber))
                    .collect(Collectors.toList());
        });
        return repo;
    }

    static PlanItemRecordRepository planItems() {
        Map<String, PlanItemRecord> rows = new LinkedHashMap<>();
        PlanItemRecordRepository repo = mock(PlanItemRecordRepository.class);

        when(repo.saveAll(anyList())).thenAnswer(inv -> {
            List<PlanItemRecord> all = inv.getArgument(0);
            for (PlanItemRecord row : all) {
                rows.put(row.getId(), row);
            }
            return all;
        });
        when(repo.save(any(PlanItemRecord.class))).thenAnswer(inv -> {
            PlanItemRecord row = inv.getArgument(0);
            rows.put(row.getId(), row);
            return row;
        });
        when(repo.countByRevisionId(anyString())).thenAnswer(inv -> {
            String revisionId = inv.getArgument(0);
            return rows.values().stream()
                    .filter(r -> Objects.equals(revisionId, r.getRevisionId())).count();
        });
        when(repo.findByRevisionIdOrderByStartAtAsc(anyString())).thenAnswer(inv -> {
            String revisionId = inv.getArgument(0);
            return rows.values().stream()
                    .filter(r -> Objects.equals(revisionId, r.getRevisionId()))
                    .sorted(Comparator.comparing(PlanItemRecord::getStartAt))
                    .collect(Collectors.toList());
        });
        when(repo.existsByRevisionIdAndItemId(anyString(), anyString())).thenAnswer(inv -> {
            String revisionId = inv.getArgument(0);
            String itemId = inv.getArgument(1);
            return rows.values().stream().anyMatch(r -> Objects.equals(revisionId, r.getRevisionId())
                    && Objects.equals(itemId, r.getItemId()));
        });
        when(repo.findByItemIdOrderByRevisionIdAsc(anyString())).thenAnswer(inv -> {
            String itemId = inv.getArgument(0);
            return rows.values().stream()
                    .filter(r -> Objects.equals(itemId, r.getItemId()))
                    .sorted(Comparator.comparing(PlanItemRecord::getRevisionId))
                    .collect(Collectors.toList());
        });
        return repo;
    }

    static PlanConstraintRecordRepository planConstraints() {
        Map<String, PlanConstraintRecord> rows = new LinkedHashMap<>();
        PlanConstraintRecordRepository repo = mock(PlanConstraintRecordRepository.class);

        when(repo.saveAll(anyList())).thenAnswer(inv -> {
            List<PlanConstraintRecord> all = inv.getArgument(0);
            for (PlanConstraintRecord row : all) {
                rows.put(row.getId(), row);
            }
            return all;
        });
        when(repo.save(any(PlanConstraintRecord.class))).thenAnswer(inv -> {
            PlanConstraintRecord row = inv.getArgument(0);
            rows.put(row.getId(), row);
            return row;
        });
        when(repo.findByRevisionIdAndPlanItemIdIsNull(anyString())).thenAnswer(inv -> {
            String revisionId = inv.getArgument(0);
            return rows.values().stream()
                    .filter(r -> Objects.equals(revisionId, r.getRevisionId()))
                    .filter(r -> r.getPlanItemId() == null)
                    .collect(Collectors.toList());
        });
        when(repo.findByRevisionIdAndPlanItemId(anyString(), anyString())).thenAnswer(inv -> {
            String revisionId = inv.getArgument(0);
            String planItemId = inv.getArgument(1);
            return rows.values().stream()
                    .filter(r -> Objects.equals(revisionId, r.getRevisionId()))
                    .filter(r -> Objects.equals(planItemId, r.getPlanItemId()))
                    .collect(Collectors.toList());
        });
        when(repo.findByPlanItemId(anyString())).thenAnswer(inv -> {
            String planItemId = inv.getArgument(0);
            return rows.values().stream()
                    .filter(r -> Objects.equals(planItemId, r.getPlanItemId()))
                    .collect(Collectors.toList());
        });
        when(repo.findByRevisionId(anyString())).thenAnswer(inv -> {
            String revisionId = inv.getArgument(0);
            return rows.values().stream()
                    .filter(r -> Objects.equals(revisionId, r.getRevisionId()))
                    .collect(Collectors.toList());
        });
        return repo;
    }

    // ─────────────────────────── world_object ───────────────────────────

    static WorldObjectRecordRepository worldObjects() {
        Map<String, WorldObjectRecord> rows = new LinkedHashMap<>();
        WorldObjectRecordRepository repo = mock(WorldObjectRecordRepository.class);

        when(repo.save(any(WorldObjectRecord.class))).thenAnswer(inv -> {
            WorldObjectRecord row = inv.getArgument(0);
            rows.put(row.getId(), row);
            return row;
        });
        when(repo.findByOwnerWorldIdOrderByDisplayNameAsc(anyString())).thenAnswer(inv -> {
            String worldId = inv.getArgument(0);
            return rows.values().stream()
                    .filter(r -> Objects.equals(worldId, r.getOwnerWorldId()))
                    .sorted(Comparator.comparing(WorldObjectRecord::getDisplayName))
                    .collect(Collectors.toList());
        });
        when(repo.existsByOwnerWorldIdAndId(anyString(), anyString())).thenAnswer(inv -> {
            String worldId = inv.getArgument(0);
            String id = inv.getArgument(1);
            return rows.values().stream()
                    .anyMatch(r -> Objects.equals(worldId, r.getOwnerWorldId())
                            && Objects.equals(id, r.getId()));
        });
        when(repo.countByOwnerWorldId(anyString())).thenAnswer(inv -> {
            String worldId = inv.getArgument(0);
            return rows.values().stream().filter(r -> Objects.equals(worldId, r.getOwnerWorldId())).count();
        });
        return repo;
    }

    // ─────────────────────────── 工具 ───────────────────────────

    /**
     * 模拟 {@code @PrePersist} 的 id 赋值。
     *
     * <p>真库路径上这一步由 Hibernate 在 flush 之前调用实体的 {@code assignId()} 完成;
     * 替身不经过 Hibernate, 所以必须自己做 —— 否则 {@code getId()} 永远是
     * {@code null}, 而所有"按 id 回写"的路径都会静默失效。
     */
    private static void assignId(java.util.function.Supplier<String> getter,
                                 java.util.function.Consumer<String> setter,
                                 String prefix, int[] seq) {
        if (getter.get() == null) {
            setter.accept(prefix + "-" + (++seq[0]));
        }
    }

    private FakeRepositories() {
    }
}
