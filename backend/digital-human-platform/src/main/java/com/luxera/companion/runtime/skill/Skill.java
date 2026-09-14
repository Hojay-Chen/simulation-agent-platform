package com.luxera.companion.runtime.skill;

/**
 * 技能(§49-§51): 一个可组合能力包。
 * 与人格/身份/关系分离 —— Skill 只负责"当我要做某件事时应该怎么做"。
 */
public record Skill(String id, String name, String content) {
}
