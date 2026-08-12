package com.dfygt.dfetl.server.repository;

import com.dfygt.dfetl.server.entity.DfetlField;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface DfetlFieldRepository extends JpaRepository<DfetlField, Long> {

    List<DfetlField> findByDatasetIdOrderByFieldOrderAscIdAsc(Long datasetId);
}
