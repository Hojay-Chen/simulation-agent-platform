package com.luxera.companion.human.mind.relationship;

import java.util.Objects;

/**
 * V2.2 §3.4.6 —— <b>创建 agent 时那份"她是谁的"配置</b>, 只取关系网需要的那几个字段。
 *
 * <h2>为什么这里不是完整的人格配置</h2>
 * 因为完整的人格(性格、说话风格、喜好、作息)属于 {@code persona} 那一层,
 * 而 {@link RelationshipGraph#bootstrap} 只用到其中三件事:
 *
 * <ol>
 *   <li>主人<b>叫什么</b> —— 她去认识第一个人时用的名字;</li>
 *   <li>主人与她<b>是什么关系</b> —— §3.4.6 的初始化流程第 ① 步写明"由 persona 指定";</li>
 *   <li>初始<b>亲密/信任值</b> —— 第 ② 步的"带上初始的亲密值"。</li>
 * </ol>
 *
 * <p>本类把这三件事抽出来, 于是 {@code human.mind} 这一层<b>不需要知道 persona 的存储形态</b>
 * (那边现在是一张 JPA 实体表)。这不是为了解耦而解耦: 直接依赖那边的实体会让
 * "关系网怎么初始化"这件事被一次数据库改动牵动, 而它本来只是一次内存里的装配。
 *
 * <h2>它刻意不做什么</h2>
 * <ul>
 *   <li><b>不带账号</b>。账号是 {@code bootstrap} 的第二个参数, 因为它们来自
 *       不一样的地方: 人格来自配置, 账号来自聊天平台(§3.4.6 第 ③ 步的
 *       {@code CompanionDirectoryPort})。合成一个对象会掩盖"这两样东西可能对不上"
 *       这件事 —— 而那正是初始化最容易出错的地方;</li>
 *   <li><b>不做默认值猜测</b>。名字为空、关系为空都会被拒绝。一个"默认叫'用户'"
 *       的兜底会让配置错误静默地变成一个人格对象, 而她的通讯录里会永远多一个叫
 *       "用户"的人。</li>
 * </ul>
 */
public record PersonaSpec(
        String ownerName,
        String ownerRelation,
        double initialCloseness,
        double initialTrust) {

    public PersonaSpec {
        Objects.requireNonNull(ownerName, "主人必须有名字 —— 见本类" + "不做默认值猜测");
        Objects.requireNonNull(ownerRelation, "主人与她的关系必须给出 —— 见 Relationship 关于 kind 的论证");
        ownerName = ownerName.trim();
        ownerRelation = ownerRelation.trim();
        if (ownerName.isEmpty()) {
            throw new IllegalArgumentException(
                    "主人的名字不能是空白 —— 她第一次认识的人不能是一个没有名字的人");
        }
        if (ownerRelation.isEmpty()) {
            throw new IllegalArgumentException(
                    "主人与她的关系不能是空白 —— 至少也要是 " + Relationship.KIND_ACQUAINTANCE);
        }
        requireUnit(initialCloseness, "初始亲密值");
        requireUnit(initialTrust, "初始信任值");
    }

    /**
     * 最常见的配置: 一个关系不错、彼此信任的主人。
     *
     * <p>它把两个数值<b>显式写出来</b>而不是藏在某个私有默认值里, 因为这两个数
     * 是产品参数, 会被反复调, 而它们的影响面很广 —— 见 {@code AttentionContext}
     * 里"处境折扣"那一节: 主人的消息更容易被注意到, 源头就是这里。
     */
    public static PersonaSpec owner(String ownerName, String ownerRelation,
                                    double initialCloseness, double initialTrust) {
        return new PersonaSpec(ownerName, ownerRelation, initialCloseness, initialTrust);
    }

    /** 一个中性的起点 —— 用于测试与"配置里什么都没写"时的显式选择。 */
    public static PersonaSpec neutral(String ownerName) {
        return new PersonaSpec(ownerName, Relationship.KIND_ACQUAINTANCE, 0.3, 0.5);
    }

    public String describe() {
        return "主人 " + ownerName + "(" + ownerRelation + ", 亲密 "
                + Math.round(initialCloseness * 100) / 100.0 + ", 信任 "
                + Math.round(initialTrust * 100) / 100.0 + ")";
    }

    @Override
    public String toString() {
        return describe();
    }

    private static void requireUnit(double value, String what) {
        if (Double.isNaN(value) || value < 0.0 || value > 1.0) {
            throw new IllegalArgumentException(
                    what + "必须归一化到 [0, 1], 收到 " + value
                            + " —— 关系数值是注意力折扣的输入之一, 越界会让她的行为无法解释");
        }
    }
}
