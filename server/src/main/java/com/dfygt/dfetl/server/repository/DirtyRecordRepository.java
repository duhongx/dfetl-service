package com.dfygt.dfetl.server.repository;

import com.dfygt.dfetl.server.entity.DirtyRecord;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

public interface DirtyRecordRepository extends JpaRepository<DirtyRecord, Long> {

    Page<DirtyRecord> findByTaskId(Long taskId, Pageable pageable);

    @Modifying
    @Transactional
    @Query("DELETE FROM DirtyRecord d WHERE d.taskId = :taskId")
    void deleteByTaskId(@Param("taskId") Long taskId);

    Page<DirtyRecord> findByHandledFalse(Pageable pageable);

    long countByTaskIdAndHandledFalse(Long taskId);

    /** 支持 taskId/executionId/errorType/handled 组合过滤，任一参数为 null 时忽略该条件 */
    @Query("SELECT d FROM DirtyRecord d WHERE " +
           "(:taskId IS NULL OR d.taskId = :taskId) AND " +
           "(:executionId IS NULL OR d.executionId = :executionId) AND " +
           "(:errorType IS NULL OR d.errorType = :errorType) AND " +
           "(:handled IS NULL OR d.handled = :handled)")
    Page<DirtyRecord> findByFilter(
            @Param("taskId") Long taskId,
            @Param("executionId") Long executionId,
            @Param("errorType") String errorType,
            @Param("handled") Boolean handled,
            Pageable pageable);
}

