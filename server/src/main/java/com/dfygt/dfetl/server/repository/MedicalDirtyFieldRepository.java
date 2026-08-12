package com.dfygt.dfetl.server.repository;

import com.dfygt.dfetl.server.entity.MedicalDirtyField;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface MedicalDirtyFieldRepository extends JpaRepository<MedicalDirtyField, Long> {

    List<MedicalDirtyField> findByDirtyRowId(Long dirtyRowId);
}
