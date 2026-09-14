package com.luxera.companion.persona;

import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Map;

/** Companion / 人格相关请求响应 DTO */
public final class CompanionDtos {

    private CompanionDtos() {}

    @Data
    public static class CompileRequest {
        private String description;
    }

    @Data
    public static class CompileResponse {
        private Persona persona;
        private String preview;
    }

    @Data
    public static class PreviewRequest {
        private Persona persona;
        private String scenario;
    }

    @Data
    public static class PreviewResponse {
        private String response;
    }

    @Data
    public static class CreateRequest {
        private Persona persona;
        private String greeting;
        /** 用户显式选择的关系类型(lover/best_friend/friend/...), 缺省取 persona.relationship.type */
        private String relationshipType;
    }

    @Data
    public static class UpdatePersonaRequest {
        private String description;
        private String reason;
    }

    @Data
    public static class CompanionDto {
        private String id;
        private String name;
        private String gender;
        private Integer age;
        private LocalDate birthDate;
        private LocalDate nextBirthday;
        private Place birthPlace;
        private String nationality;
        private String timezone;
        private String greeting;
        private Persona persona;
        private String relationshipType;
        private String relationshipStage;
        private LocalDateTime createdAt;
    }

    @Data
    public static class LifeEventDto {
        private String id;
        private String type;
        private String subtype;
        private String title;
        private String description;
        private LocalDate startTime;
        private LocalDate endTime;
        private double importance;
        private double emotionalSignificance;
    }
}
