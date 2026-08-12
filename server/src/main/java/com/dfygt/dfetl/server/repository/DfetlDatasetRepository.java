package com.dfygt.dfetl.server.repository;

import com.dfygt.dfetl.server.entity.DfetlDataset;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.util.Optional;

public interface DfetlDatasetRepository
        extends JpaRepository<DfetlDataset, Long>, JpaSpecificationExecutor<DfetlDataset> {

    Optional<DfetlDataset> findFirstByDatasetCodeIgnoreCase(String datasetCode);
}
