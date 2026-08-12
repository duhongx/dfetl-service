package com.dfygt.dfetl.server.repository;

import com.dfygt.dfetl.server.entity.DfetlMessagePolicy;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface DfetlMessagePolicyRepository extends JpaRepository<DfetlMessagePolicy, Long> {
    Optional<DfetlMessagePolicy> findByDatasetId(Long datasetId);
}
