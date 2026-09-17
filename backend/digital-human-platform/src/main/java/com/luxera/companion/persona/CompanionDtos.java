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
        /**
         * 账号ID —— 与 {@code name} 正交: 名字可以重名(LLM 生成的描述相近时会收敛到同一个,
         * 用户的 7 个 Agent 都叫「小满」就是这么来的), 账号ID 是哪个的唯一答案。
         * 可能是 null(补号还没跑), 前端要能接受。
         */
        private String handle;
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
    public static class UpdateHandleRequest {
        /** 用户想要的账号ID。大小写会被归一成小写, 校验规则见 {@code Handles} */
        private String handle;
    }

    /**
     * 账号ID 的当前值 + 改号配额 —— 界面要显示的那点东西。
     *
     * <p>比直接返回 {@code HandleQuota} 多一层, 是因为 record 的序列化形状由 record 定死,
     * 而 {@code nextChangeAt} 直接吐 {@code LocalDateTime} 会带上纳秒。这里转成日期时间
     * 字符串, 前端不必去猜精度。
     */
    @Data
    public static class HandleView {
        private String handle;
        /** 最近 365 天内已改次数 */
        private int used;
        private int limit;
        /** 还能改几次 —— 界面显示这个, 不显示 used(减法在每个调用点做, 总有人做反) */
        private int remaining;
        /** 额度用尽时才有值: 最早能再改的日期 */
        private LocalDateTime nextChangeAt;

        public static HandleView of(com.luxera.companion.person.HandleQuota q) {
            HandleView v = new HandleView();
            v.setHandle(q.handle());
            v.setUsed(q.used());
            v.setLimit(q.limit());
            v.setRemaining(q.remaining());
            v.setNextChangeAt(q.nextChangeAt());
            return v;
        }
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
