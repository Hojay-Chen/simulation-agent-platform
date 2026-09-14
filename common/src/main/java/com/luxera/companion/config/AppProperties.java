package com.luxera.companion.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * 全局业务配置,绑定 application.yml 的 app.* 配置树。
 */
@Component
@ConfigurationProperties(prefix = "app")
@Data
public class AppProperties {

    private Jwt jwt = new Jwt();
    private Llm llm = new Llm();
    private Agent agent = new Agent();
    private Scheduler scheduler = new Scheduler();
    private Proactive proactive = new Proactive();
    private Embedding embedding = new Embedding();

    @Data
    public static class Jwt {
        private String secret;
        private long expirationMs = 604800000L;
    }

    @Data
    public static class Llm {
        /** openai-compatible | anthropic | mock */
        private String provider = "openai-compatible";
        private String baseUrl = "https://api.deepseek.com";
        private String apiKey = "";
        private String chatModel = "deepseek-chat";
        private double temperature = 0.9;
        private double structuredTemperature = 0.3;
        private int maxTokens = 2048;
        private int timeoutSeconds = 120;
        /** api-key 为空时自动降级为 mock */
        private boolean mockFallback = true;
        /** 模型用途路由(设计文档 §25): 按任务类型可分别指定模型/温度, 缺省用 chat-model */
        private Map<String, Purpose> purpose = new java.util.HashMap<>();
    }

    @Data
    public static class Purpose {
        private String model;
        private Double temperature;
    }

    @Data
    public static class Agent {
        private String intentExtraction = "heuristic";
        private int memoryTopN = 12;
        private int contextMaxTokens = 12000;
        private int recentMessages = 20;
        private double memoryMinStrength = 0.02;
        private int workingMemoryTtlMinutes = 720;
    }

    @Data
    public static class Embedding {
        private String baseUrl = "https://api.siliconflow.cn/v1";
        private String apiKey = "";
        private String model = "BAAI/bge-large-zh-v1.5";
        private int dimension = 1024;
    }

    @Data
    public static class Scheduler {
        private String dailyReflectionCron = "0 17 3 * * *";
        private String weeklyReflectionCron = "0 0 5 * * MON";
        private String proactiveCron = "0 */15 * * * *";
        private String birthdayCron = "0 5 8 * * *";
        // 生命内核
        private String lifeTickCron = "0 */10 * * * *";
        private String thoughtMaintenanceCron = "0 */30 * * * *";
        private String emotionMaintenanceCron = "0 45 * * * *";
        private String openLoopCron = "0 */15 * * * *";
        private String memoryConsolidationCron = "0 0 4 * * *";
    }

    @Data
    public static class Proactive {
        private int dndStartHour = 23;
        private int dndEndHour = 8;
        private int minIntervalHours = 4;
        private int maxNotificationsPerDay = 3;
    }

    /** 供定时任务读取自定义 cron 用的便利方法 */
    public Map<String, String> cronMap() {
        Map<String, String> m = new HashMap<>();
        m.put("daily-reflection", scheduler.getDailyReflectionCron());
        m.put("proactive", scheduler.getProactiveCron());
        m.put("birthday", scheduler.getBirthdayCron());
        return m;
    }
}
