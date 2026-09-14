package com.luxera.companion.relationship;

import java.util.Map;

/**
 * §六~§七 关系类型: 创建伴侣时用户选择"你们是什么关系", 这是 Agent 世界中的真实关系状态,
 * 不是 Prompt。不同类型 → 不同的初始关系维度(熟悉/信任/亲密/好感/张力/双向性/依赖)。
 */
public final class RelationshipTypes {

    public static final String LOVER = "lover";
    public static final String BEST_FRIEND = "best_friend";
    public static final String FRIEND = "friend";
    public static final String SISTER = "sister";
    public static final String BROTHER = "brother";
    public static final String COLLEAGUE = "colleague";
    public static final String CLASSMATE = "classmate";
    public static final String FAMILY = "family";
    public static final String MENTOR = "mentor";
    public static final String OTHER = "other";

    /** 前端可选项(顺序即展示顺序) */
    public static final java.util.List<String> ALL = java.util.List.of(
            LOVER, BEST_FRIEND, FRIEND, SISTER, BROTHER, COLLEAGUE, CLASSMATE, FAMILY, MENTOR, OTHER);

    private static final Map<String, String> ZH = Map.ofEntries(
            Map.entry(LOVER, "恋人"),
            Map.entry(BEST_FRIEND, "最好的朋友"),
            Map.entry(FRIEND, "朋友"),
            Map.entry(SISTER, "姐姐/妹妹"),
            Map.entry(BROTHER, "哥哥/弟弟"),
            Map.entry(COLLEAGUE, "同事"),
            Map.entry(CLASSMATE, "同学"),
            Map.entry(FAMILY, "家人"),
            Map.entry(MENTOR, "前辈/老师"),
            Map.entry(OTHER, "自定义"));

    /** 每种关系的初始维度画像(familiarity/trust/intimacy/affection/tension/reciprocity/respect/dependence) */
    private static final Map<String, double[]> INIT = Map.ofEntries(
            Map.entry(LOVER, new double[]{0.45, 0.4, 0.55, 0.65, 0.05, 0.7, 0.4, 0.5}),
            Map.entry(BEST_FRIEND, new double[]{0.6, 0.55, 0.45, 0.6, 0.03, 0.75, 0.5, 0.35}),
            Map.entry(FRIEND, new double[]{0.35, 0.3, 0.2, 0.4, 0.02, 0.55, 0.35, 0.15}),
            Map.entry(SISTER, new double[]{0.7, 0.6, 0.55, 0.7, 0.05, 0.8, 0.55, 0.4}),
            Map.entry(BROTHER, new double[]{0.65, 0.6, 0.5, 0.65, 0.04, 0.75, 0.5, 0.35}),
            Map.entry(COLLEAGUE, new double[]{0.3, 0.25, 0.05, 0.1, 0.03, 0.4, 0.3, 0.05}),
            Map.entry(CLASSMATE, new double[]{0.3, 0.25, 0.08, 0.15, 0.02, 0.45, 0.3, 0.08}),
            Map.entry(FAMILY, new double[]{0.75, 0.65, 0.6, 0.75, 0.08, 0.85, 0.6, 0.45}),
            Map.entry(MENTOR, new double[]{0.3, 0.35, 0.1, 0.2, 0.01, 0.5, 0.7, 0.2}),
            Map.entry(OTHER, new double[]{0.2, 0.2, 0.1, 0.2, 0.02, 0.5, 0.3, 0.1}));

    private RelationshipTypes() {
    }

    public static boolean isValid(String type) {
        return type != null && ALL.contains(type);
    }

    /** 归一化: 旧值(girlfriend/boyfriend/女朋友/男朋友) → 新枚举 */
    public static String normalize(String type) {
        if (type == null) return null;
        return switch (type) {
            case "girlfriend", "boyfriend", "女朋友", "男朋友", "恋人" -> LOVER;
            case "best_friend", "闺蜜", "兄弟" -> BEST_FRIEND;
            case "sibling", "姐姐", "妹妹", "哥哥", "弟弟" -> SISTER;
            case "colleague", "同事" -> COLLEAGUE;
            case "classmate", "同学" -> CLASSMATE;
            case "family", "家人", "亲戚" -> FAMILY;
            case "mentor", "老师", "前辈" -> MENTOR;
            default -> type;
        };
    }

    public static String zh(String type) {
        return ZH.getOrDefault(type == null ? "" : type, "朋友");
    }

    /** 按类型应用初始维度(缺省用 OTHER 画像) */
    public static void applyInitial(Relationship r, String type) {
        String normalized = normalize(type);
        if (normalized == null || !isValid(normalized)) {
            normalized = OTHER;
        }
        double[] v = INIT.getOrDefault(normalized, INIT.get(OTHER));
        r.setRelationshipType(normalized);
        r.setFamiliarity(clamp(v[0]));
        r.setTrust(clamp(v[1]));
        r.setIntimacy(clamp(v[2]));
        r.setAffection(clamp(v[3]));
        r.setTension(clamp(v[4]));
        r.setReciprocity(clamp(v[5]));
        r.setRespect(clamp(v[6]));
        r.setDependence(clamp(v[7]));
    }

    private static double clamp(double v) {
        return Math.max(0, Math.min(1, v));
    }
}
