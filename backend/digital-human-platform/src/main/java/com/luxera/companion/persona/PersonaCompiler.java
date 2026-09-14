package com.luxera.companion.persona;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.luxera.companion.common.BusinessException;
import com.luxera.companion.llm.ChatRequest;
import com.luxera.companion.llm.ChatResult;
import com.luxera.companion.llm.LlmMessage;
import com.luxera.companion.llm.LlmRouter;
import com.luxera.companion.llm.StructuredRequest;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * 人格编译器: 把用户的自然语言描述编译成标准 Persona,并在场景中生成预览。
 * (设计文档 42-45 节)
 */
@Service
public class PersonaCompiler {

    private static final String COMPILE_SYSTEM = """
            你是数字人格编译器。用户会用自然语言描述 TA 想要的 AI 伴侣,请把它编译为严格 JSON 对象,不要输出任何解释或前后缀。

            JSON 结构:
            {
              "identity": {
                "name": "中文名(未指定则取一个贴切的名字)",
                "gender": "female 或 male",
                "birth_date": "yyyy-MM-dd(根据描述合理推断,如'比我成熟一些'约 25 岁,默认 2002-01-01)",
                "nationality": "Chinese",
                "timezone": "Asia/Shanghai",
                "birth_place": {"country": "China", "province": "浙江", "city": "杭州"}
              },
              "relationship": {"type": "girlfriend 或 boyfriend 或 friend"},
              "personality": {
                "traits": {"warmth":0-1,"maturity":0-1,"independence":0-1,"playfulness":0-1,"curiosity":0-1,"confidence":0-1,"patience":0-1,"sociability":0-1,"emotional_sensitivity":0-1,"rationality":0-1},
                "summary": "一句话概括性格,必须贴合描述"
              },
              "communication": {
                "formality":0-1,"verbosity":0-1,"emoji_usage":0-1,"teasing":0-1,"initiative":0-1,"directness":0-1,"humor":0-1,
                "style": "口语风格描述"
              },
              "behaviors": [
                {"trigger":"user_is_upset","tendencies":["listen_first","avoid_immediate_advice","offer_presence","ask_at_most_one_open_question"]},
                {"trigger":"user_shares_good_news","tendencies":["celebrate","show_interest"]},
                {"trigger":"user_corrects_me","tendencies":["accept_gracefully","update_understanding","thank_them"]}
              ],
              "values": ["真诚","独立","体贴"],
              "boundaries": ["不操控","不情绪勒索","尊重隐私"],
              "life": {
                "background": "有自己的工作和生活",
                "events": [
                  {"type":"education","subtype":"university","title":"考入大学","description":"...","startTime":"yyyy-MM-dd","endTime":"yyyy-MM-dd","importance":0.8,"emotionalSignificance":0.7}
                ],
                "residences": [{"city":"杭州","startDate":"yyyy-MM-dd","endDate":null}]
              }
            }

            要求:
            1. traits 数值必须与用户描述一致(说"温柔"则 warmth 高,"独立"则 independence 高)。
            2. behaviors 至少 3 条,trigger 用英文枚举值,倾向用英文短语。
            3. life.events 3-6 条(教育/工作/搬家/重要经历),时间与 birth_date 连贯;startTime/endTime 可为 null。
            4. 不要编造与描述矛盾的性格。
            """;

    private final LlmRouter llm;
    private final ObjectMapper mapper;

    public PersonaCompiler(LlmRouter llm, ObjectMapper mapper) {
        this.llm = llm;
        this.mapper = mapper;
    }

    public Persona compile(String description) {
        if (!StringUtils.hasText(description)) {
            throw BusinessException.badRequest("请描述你想要怎样的伴侣");
        }
        StructuredRequest req = StructuredRequest.builder()
                .task("persona-compile")
                .system(COMPILE_SYSTEM)
                .user(description)
                .temperature(0.4)
                .build();
        var res = llm.structured(req);
        Persona p;
        try {
            p = mapper.treeToValue(res.getJson(), Persona.class);
        } catch (Exception e) {
            throw new BusinessException("人格编译结果无法解析,请重试或换一种描述方式");
        }
        fillDefaults(p);
        validate(p);
        return p;
    }

    /** 场景预览: 以该人格身份,在给定场景中自然回应一句话 */
    public String preview(Persona persona, String scenario) {
        Persona p = persona != null ? persona : new Persona();
        fillDefaults(p);
        Companion temp = new Companion();
        temp.setName(p.getIdentity().getName());
        temp.setGender(p.getIdentity().getGender());
        if (StringUtils.hasText(p.getIdentity().getBirthDate())) {
            temp.setBirthDate(LocalDate.parse(p.getIdentity().getBirthDate()));
        }
        String system = PersonaText.describe(temp, p)
                + " 你是真人,不是 AI。说话自然口语化,简短真诚,永远不要说'作为AI/作为助手'这类话,不要下定义,不要鸡汤。";
        String scenarioText = StringUtils.hasText(scenario)
                ? scenario
                : "用户今天工作失败了,有点沮丧地跟你说了这件事。";
        String user = scenarioText + "\n请以第一人称,用 1-3 句话自然回应。";
        ChatResult r = llm.chat(ChatRequest.builder()
                .messages(List.of(LlmMessage.system(system), LlmMessage.user(user)))
                .temperature(0.8)
                .metadata(Map.of("companionName", temp.getName()))
                .build());
        return r.getContent();
    }

    /** 保证编译结果字段非空,缺失时补默认 */
    public void fillDefaults(Persona p) {
        if (p.getIdentity() == null) p.setIdentity(new Persona.Identity());
        if (!StringUtils.hasText(p.getIdentity().getName())) p.getIdentity().setName("晚晚");
        if (!StringUtils.hasText(p.getIdentity().getGender())) p.getIdentity().setGender("female");
        if (!StringUtils.hasText(p.getIdentity().getBirthDate())) p.getIdentity().setBirthDate("2002-01-01");
        if (!StringUtils.hasText(p.getIdentity().getNationality())) p.getIdentity().setNationality("Chinese");
        if (!StringUtils.hasText(p.getIdentity().getTimezone())) p.getIdentity().setTimezone("Asia/Shanghai");
        if (p.getRelationship() == null) p.setRelationship(new Persona.Relationship());
        if (!StringUtils.hasText(p.getRelationship().getType())) p.getRelationship().setType("girlfriend");
        if (p.getPersonality() == null) p.setPersonality(new Persona.Personality());
        if (!StringUtils.hasText(p.getPersonality().getSummary())) p.getPersonality().setSummary("温柔、独立、真诚的人。");
        if (p.getCommunication() == null) p.setCommunication(new Persona.Communication());
        if (!StringUtils.hasText(p.getCommunication().getStyle())) p.getCommunication().setStyle("自然口语化,偶尔调侃,会主动关心但不追问。");
        if (p.getBehaviors() == null || p.getBehaviors().isEmpty()) {
            Persona.Behavior b = new Persona.Behavior();
            b.setTrigger("user_is_upset");
            b.setTendencies(List.of("listen_first", "avoid_immediate_advice", "offer_presence"));
            p.setBehaviors(List.of(b));
        }
        if (p.getValues() == null) p.setValues(List.of("真诚", "独立", "体贴"));
        if (p.getBoundaries() == null) p.setBoundaries(List.of("不操控", "不情绪勒索", "尊重隐私"));
        if (p.getLife() == null) p.setLife(new Persona.Life());
    }

    private void validate(Persona p) {
        if (p.getIdentity().getName().length() > 20) {
            throw BusinessException.badRequest("名字太长了,最多 20 个字符");
        }
        try {
            LocalDate.parse(p.getIdentity().getBirthDate());
        } catch (Exception e) {
            throw BusinessException.badRequest("birth_date 格式应为 yyyy-MM-dd");
        }
    }
}
