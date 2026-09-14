package com.luxera.companion.persona;

import com.fasterxml.jackson.databind.JsonNode;
import com.luxera.companion.llm.LlmRouter;
import com.luxera.companion.llm.StructuredRequest;
import com.luxera.companion.relationship.Relationship;
import com.luxera.companion.relationship.RelationshipService;
import com.luxera.companion.selfmodel.SelfModel;
import com.luxera.companion.usermodel.UserModelService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 人格演化 2.0(设计文档 §19/§40): Experience → 证据 → 行为适应(第1层) → 互动偏好(第2层)
 * → 性格trait微调(第3层, ±0.02) → 核心身份(第4层, 默认禁止自动)。
 * 行为适应优先写入 SelfModel, 只有充分证据才微调 traits 并生成新人格版本。
 */
@Slf4j
@Service
public class PersonaEvolutionService {

    private static final String SYSTEM = """
            你是人格演化引擎。基于相处证据, 对伴侣做分层演化(由浅入深, 越深层越保守)。
            当前人格: %s

            相处证据: %s

            输出严格 JSON, 不要输出其他内容:
            {
              "behavioral_adaptations": [{"behavior":"面对用户情绪低落时先安静陪伴","reason":"..."}],
              "interaction_preferences": [{"preference":"用户压力大时少追问","reason":"..."}],
              "trait_adjustments": [{"field":"personality.traits.warmth","delta":0.02,"reason":"..."}],
              "summary_note": "可选的人格描述更新"
            }
            规则(分层):
            - 第1层 行为适应: 优先, 描述具体行为倾向(不直接改数值)
            - 第2层 互动偏好: 次之, 描述与用户相处的方式偏好
            - 第3层 性格trait: 只有极充分证据才提, delta 绝对值 ≤ 0.02, 每次最多 2 处
            - 第4层 核心身份(values/boundaries/gender/birth/世界观): 绝不自动改
            """;

    private final PersonaService personaService;
    private final UserModelService userModelService;
    private final RelationshipService relationshipService;
    private final CompanionRepository companionRepo;
    private final com.luxera.companion.selfmodel.SelfModelService selfModelService;
    private final LlmRouter llm;

    public PersonaEvolutionService(PersonaService personaService, UserModelService userModelService,
                                   RelationshipService relationshipService, CompanionRepository companionRepo,
                                   com.luxera.companion.selfmodel.SelfModelService selfModelService,
                                   LlmRouter llm) {
        this.personaService = personaService;
        this.userModelService = userModelService;
        this.relationshipService = relationshipService;
        this.companionRepo = companionRepo;
        this.selfModelService = selfModelService;
        this.llm = llm;
    }

    /** 对所有伴侣执行人格演化 */
    @Transactional
    public List<String> runAll() {
        List<String> results = new ArrayList<>();
        for (Companion c : companionRepo.findAll()) {
            if (c.getDeletedAt() != null) continue;
            List<String> changes = evolve(c);
            if (!changes.isEmpty()) {
                results.add(c.getName() + ": " + String.join("; ", changes));
            }
        }
        return results;
    }

    /** 对单个伴侣执行人格演化,返回变更描述(空 = 无变化) */
    @Transactional
    public List<String> evolve(Companion c) {
        Persona persona = personaService.getActive(c.getId());
        List<String> changes = new ArrayList<>();
        if (persona == null) return changes;

        String evidence = buildEvidence(c, persona);
        String prompt = String.format(SYSTEM,
                personaSummary(persona),
                evidence.length() > 3000 ? evidence.substring(0, 3000) : evidence);

        try {
            var res = llm.structured(StructuredRequest.builder()
                    .task("persona-evolution")
                    .system(prompt)
                    .user("请基于以上证据提出分层演化。")
                    .temperature(0.3)
                    .build());
            JsonNode root = res.getJson();

            // 第1层: 行为适应 → SelfModel.patterns
            List<String> behaviors = new ArrayList<>();
            for (JsonNode n : root.path("behavioral_adaptations")) {
                String b = n.path("behavior").asText("");
                if (!b.isBlank()) behaviors.add(b);
            }
            // 第2层: 互动偏好 → SelfModel.preferences
            List<String> prefs = new ArrayList<>();
            for (JsonNode n : root.path("interaction_preferences")) {
                String p = n.path("preference").asText("");
                if (!p.isBlank()) prefs.add(p);
            }
            if (!behaviors.isEmpty() || !prefs.isEmpty()) {
                SelfModel sm = selfModelService.get(c.getId());
                List<String> mergedPatterns = sm != null ? new ArrayList<>(sm.getPatterns()) : new ArrayList<>();
                List<String> mergedPrefs = sm != null ? new ArrayList<>(sm.getPreferences()) : new ArrayList<>();
                for (String b : behaviors) if (!mergedPatterns.contains(b)) mergedPatterns.add(b);
                for (String p : prefs) if (!mergedPrefs.contains(p)) mergedPrefs.add(p);
                selfModelService.update(c.getId(),
                        new com.luxera.companion.selfmodel.SelfModelService.SelfModelUpdate(
                                null, mergedPrefs, mergedPatterns,
                                null, null, null, null, null),
                        "人格演化: 行为适应/互动偏好");
                changes.add("行为适应 " + behaviors.size() + " 条, 互动偏好 " + prefs.size() + " 条");
            }

            // 第3层: 性格 trait 微调(±0.02, 最多 2 处)
            int applied = 0;
            for (JsonNode adj : root.path("trait_adjustments")) {
                String field = adj.path("field").asText("");
                double rawDelta = adj.path("delta").asDouble(0);
                String reason = adj.path("reason").asText("");
                double delta = Math.max(-0.02, Math.min(0.02, rawDelta));
                if (field.startsWith("personality.traits.") && Math.abs(delta) >= 0.01) {
                    String trait = field.substring("personality.traits.".length());
                    if (persona.getPersonality() == null) persona.setPersonality(new Persona.Personality());
                    Map<String, Double> traits = persona.getPersonality().getTraits();
                    double cur = traits.getOrDefault(trait, 0.5);
                    double updated = clamp(cur + delta);
                    if (Math.abs(updated - cur) >= 0.01) {
                        traits.put(trait, updated);
                        changes.add(String.format("%s %s%.2f (%s)", trait, delta >= 0 ? "+" : "", delta, reason));
                        applied++;
                    }
                }
            }
            String summaryNote = root.path("summary_note").asText("");
            if (applied > 0) {
                if (StringUtils.hasText(summaryNote) && summaryNote.length() < 100
                        && persona.getPersonality() != null) {
                    persona.getPersonality().setSummary(summaryNote);
                }
                String reason = "人格演化: " + String.join("; ", changes);
                personaService.update(c.getId(), persona, reason, "evolution");
                log.info("[人格演化] {}: {}", c.getName(), reason);
            }
        } catch (Exception e) {
            log.warn("人格演化失败 companion={}: {}", c.getId(), e.getMessage());
        }
        return changes;
    }

    private String buildEvidence(Companion c, Persona persona) {
        StringBuilder sb = new StringBuilder();
        UserModelService.UserModelSummary summary = userModelService.summary(c.getUserId(), c.getId());
        append(sb, "用户事实", summary.facts());
        append(sb, "用户偏好", summary.preferences());
        append(sb, "用户习惯", summary.patterns());
        append(sb, "用户推测", summary.hypotheses());
        Relationship rel = relationshipService.find(c.getUserId(), c.getId());
        if (rel != null) {
            sb.append("关系: 阶段").append(rel.getRelationshipStage())
                    .append(",累计").append(rel.getMessageCount()).append("条消息,")
                    .append("熟悉度").append(round(rel.getFamiliarity()))
                    .append(",信任").append(round(rel.getTrust()))
                    .append(",亲密度").append(round(rel.getIntimacy()))
                    .append("。");
        }
        return sb.toString();
    }

    private static void append(StringBuilder sb, String label, List<String> lines) {
        if (lines == null || lines.isEmpty()) return;
        sb.append(label).append(": ");
        for (String l : lines) sb.append(l).append(";");
        sb.append(" ");
    }

    private static String personaSummary(Persona p) {
        if (p.getPersonality() == null) return "";
        StringBuilder sb = new StringBuilder();
        if (p.getPersonality().getTraits() != null && !p.getPersonality().getTraits().isEmpty()) {
            sb.append("traits: ").append(p.getPersonality().getTraits()).append(";");
        }
        if (p.getPersonality().getSummary() != null) {
            sb.append("summary: ").append(p.getPersonality().getSummary());
        }
        return sb.toString();
    }

    private static double clamp(double v) {
        return Math.max(0.1, Math.min(0.95, v));
    }

    private static double round(double v) {
        return Math.round(v * 100) / 100.0;
    }
}
