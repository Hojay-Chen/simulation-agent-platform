package com.luxera.companion.mind;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/**
 * V11 §9.2 —— 心智的持久化。
 *
 * <p>刻意只有两个方法: 心智是"每个 agent 一行、整取整存"的东西, 一旦这里长出
 * 按字段查询的方法, 就说明有人开始把心智当业务表用了(见 {@link MindState} 的类注释)。
 */
public interface MindStateRepository extends JpaRepository<MindState, String> {

    Optional<MindState> findByCompanionId(String companionId);
}
