package com.dfygt.dfetl.server.external.repository;

import com.dfygt.dfetl.server.external.entity.ExternalTaskBatchRequest;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface ExternalTaskBatchRequestRepository extends JpaRepository<ExternalTaskBatchRequest, Long> {

    Optional<ExternalTaskBatchRequest> findByExternalBatchId(String externalBatchId);

    /** 同一 requestId 的并发创建在 PostgreSQL 事务内串行化，避免重复落任务。 */
    @Query(value = "SELECT pg_advisory_xact_lock(hashtext(:requestId))", nativeQuery = true)
    Object acquireRequestLock(@Param("requestId") String requestId);
}
