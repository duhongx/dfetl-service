package com.dfygt.dfetl.server.repository;

import com.dfygt.dfetl.server.entity.DfetlPrecheckIssue;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DfetlPrecheckIssueRepository extends JpaRepository<DfetlPrecheckIssue, Long> {
    Page<DfetlPrecheckIssue> findByRunId(Long runId, Pageable pageable);
    long countByRunIdAndSeverityAndRemediationStatusNot(Long runId, String severity, String remediationStatus);
}
