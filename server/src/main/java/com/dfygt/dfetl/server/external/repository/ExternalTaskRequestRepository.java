package com.dfygt.dfetl.server.external.repository;

import com.dfygt.dfetl.server.external.entity.ExternalTaskRequest;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ExternalTaskRequestRepository extends JpaRepository<ExternalTaskRequest, Long> {

    Optional<ExternalTaskRequest> findByExternalRequestId(String externalRequestId);

    List<ExternalTaskRequest> findByTaskId(Long taskId);

    List<ExternalTaskRequest> findByExternalBatchIdOrderByIdAsc(String externalBatchId);

    Optional<ExternalTaskRequest> findByExternalBatchIdAndBatchItemKey(String externalBatchId, String batchItemKey);

    void deleteByTaskId(Long taskId);
}
