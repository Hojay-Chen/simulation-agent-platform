package com.luxera.companion.persona;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface CompanionRepository extends JpaRepository<Companion, String> {
    List<Companion> findByUserIdAndDeletedAtIsNullOrderByCreatedAtAsc(String userId);
    long countByUserIdAndDeletedAtIsNull(String userId);

    /** 全部已删除的 Agent —— 只给 {@code GhostChatSweeper} 的一次性对账用。 */
    List<Companion> findByDeletedAtIsNotNull();
}
