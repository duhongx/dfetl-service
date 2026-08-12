package com.dfygt.dfetl.server.repository;

import com.dfygt.dfetl.server.entity.DfetlSyncPolicy;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface DfetlSyncPolicyRepository extends JpaRepository<DfetlSyncPolicy, Long> {
    Optional<DfetlSyncPolicy> findByDatasetId(Long datasetId);
}
