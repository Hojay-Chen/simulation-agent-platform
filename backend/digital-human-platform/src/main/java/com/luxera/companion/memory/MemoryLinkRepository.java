package com.luxera.companion.memory;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface MemoryLinkRepository extends JpaRepository<MemoryLink, String> {
    List<MemoryLink> findByFromMemoryId(String fromMemoryId);
    List<MemoryLink> findByToMemoryId(String toMemoryId);
    List<MemoryLink> findByFromMemoryIdIn(List<String> ids);
    List<MemoryLink> findByToMemoryIdIn(List<String> ids);
    void deleteByFromMemoryIdInOrToMemoryIdIn(List<String> fromIds, List<String> toIds);
}
