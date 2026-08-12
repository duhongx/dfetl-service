package com.dfygt.dfetl.server.external.repository;

import com.dfygt.dfetl.server.external.entity.ExternalTaskBatchOperationAudit;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ExternalTaskBatchOperationAuditRepository
        extends JpaRepository<ExternalTaskBatchOperationAudit, Long> {
}
