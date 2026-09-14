package com.luxera.companion.state;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface AgentStateRepository extends JpaRepository<AgentState, String> {
    Optional<AgentState> findByCompanionId(String companionId);
}
