package com.luxera.companion.persona;

import lombok.Data;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 编译后的人格模型(存 JSONB)。
 * 数据库是人格的源;Prompt 只是它的运行时投影。
 */
@Data
public class Persona {

    private Identity identity;
    private Relationship relationship;
    private Personality personality;
    private Communication communication;
    private List<Behavior> behaviors = new ArrayList<>();
    private List<String> values = new ArrayList<>();
    private List<String> boundaries = new ArrayList<>();
    private Life life;

    @Data
    public static class Identity {
        private String name;
        private String gender;
        /** ISO 日期 yyyy-MM-dd */
        private String birthDate;
        private String nationality;
        private String timezone;
        private Place birthPlace;
    }

    @Data
    public static class Relationship {
        private String type;
    }

    @Data
    public static class Personality {
        /** 连续维度 trait 值,不直接暴露给 LLM */
        private Map<String, Double> traits = new HashMap<>();
        private String summary;
    }

    @Data
    public static class Communication {
        private double formality;
        private double verbosity;
        private double emojiUsage;
        private double teasing;
        private double initiative;
        private double directness;
        private double humor;
        private String style;
    }

    @Data
    public static class Behavior {
        private String trigger;
        private List<String> tendencies = new ArrayList<>();
    }

    @Data
    public static class Life {
        private String background;
        private List<LifeEventDto> events = new ArrayList<>();
        private List<Residence> residences = new ArrayList<>();
    }

    @Data
    public static class LifeEventDto {
        private String type;
        private String subtype;
        private String title;
        private String description;
        /** ISO 日期 */
        private String startTime;
        private String endTime;
        private Double importance;
        private Double emotionalSignificance;
    }

    @Data
    public static class Residence {
        private String city;
        private String startDate;
        private String endDate;
    }
}
