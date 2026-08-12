package com.dfygt.dfetl.server.repository;

import com.dfygt.dfetl.server.entity.MedicalDirtyRow;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface MedicalDirtyRowRepository extends JpaRepository<MedicalDirtyRow, Long> {

    Optional<MedicalDirtyRow> findByExecutionIdAndDatasetCodeAndSourceRowHash(
            Long executionId,
            String datasetCode,
            String sourceRowHash);

    long countByTaskIdAndStatus(Long taskId, String status);

    @Query("SELECT r FROM MedicalDirtyRow r WHERE " +
            "(:taskId IS NULL OR r.taskId = :taskId) AND " +
            "(:executionId IS NULL OR r.executionId = :executionId) AND " +
            "(:datasetCode IS NULL OR r.datasetCode = :datasetCode) AND " +
            "(:ownerName IS NULL OR r.ownerName = :ownerName) AND " +
            "(:status IS NULL OR r.status = :status) AND " +
            "(:severity IS NULL OR r.severity = :severity)")
    Page<MedicalDirtyRow> findByFilter(
            @Param("taskId") Long taskId,
            @Param("executionId") Long executionId,
            @Param("datasetCode") String datasetCode,
            @Param("ownerName") String ownerName,
            @Param("status") String status,
            @Param("severity") String severity,
            Pageable pageable);
}
