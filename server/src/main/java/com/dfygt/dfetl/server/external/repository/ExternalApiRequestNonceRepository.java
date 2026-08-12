package com.dfygt.dfetl.server.external.repository;

import com.dfygt.dfetl.server.external.entity.ExternalApiRequestNonce;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ExternalApiRequestNonceRepository extends JpaRepository<ExternalApiRequestNonce, Long> {

    boolean existsByClientIdAndNonce(String clientId, String nonce);
}
