package com.dfygt.dfetl.server.external.repository;

import com.dfygt.dfetl.server.external.entity.ExternalApiClient;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface ExternalApiClientRepository extends JpaRepository<ExternalApiClient, Long> {

    Optional<ExternalApiClient> findByClientId(String clientId);
}
