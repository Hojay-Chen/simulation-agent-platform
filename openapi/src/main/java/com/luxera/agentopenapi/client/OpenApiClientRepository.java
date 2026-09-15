package com.luxera.agentopenapi.client;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface OpenApiClientRepository extends JpaRepository<OpenApiClientRecord, String> {

    Optional<OpenApiClientRecord> findByApiKeyHashAndStatus(String apiKeyHash, String status);

    Optional<OpenApiClientRecord> findByApiKeyHash(String apiKeyHash);

    List<OpenApiClientRecord> findAllByStatusOrderByCreatedAtDesc(String status);

    boolean existsByName(String name);
}
