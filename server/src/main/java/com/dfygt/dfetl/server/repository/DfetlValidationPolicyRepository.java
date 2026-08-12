package com.dfygt.dfetl.server.repository;

import com.dfygt.dfetl.server.entity.DfetlValidationPolicy;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface DfetlValidationPolicyRepository extends JpaRepository<DfetlValidationPolicy, Long> {
    Optional<DfetlValidationPolicy> findByDatasetId(Long datasetId);
}
