package com.dfygt.dfetl.server.repository;

import com.dfygt.dfetl.server.entity.DfetlPrecheckExport;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface DfetlPrecheckExportRepository extends JpaRepository<DfetlPrecheckExport, Long> {

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query("""
            UPDATE DfetlPrecheckExport export
               SET export.status = 'RUNNING',
                   export.startedAt = :startedAt,
                   export.errorMessage = NULL
             WHERE export.id = :exportId
               AND export.status = 'PENDING'
            """)
    int claimPending(@Param("exportId") Long exportId, @Param("startedAt") Instant startedAt);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query("""
            UPDATE DfetlPrecheckExport export
               SET export.status = 'FAILED',
                   export.errorMessage = '服务重启或导出超时，未完成的导出已终止，请重新创建导出',
                   export.finishedAt = :finishedAt
             WHERE export.status = 'RUNNING'
               AND export.startedAt < :cutoff
            """)
    int failInterruptedRunning(
            @Param("cutoff") Instant cutoff,
            @Param("finishedAt") Instant finishedAt);

    List<DfetlPrecheckExport> findTop20ByStatusOrderByCreatedAtAsc(String status);

    List<DfetlPrecheckExport> findTop100ByStatusInAndExpiresAtBeforeOrderByExpiresAtAsc(
            List<String> statuses,
            Instant expiresBefore);

    Optional<DfetlPrecheckExport> findByRequestKey(String requestKey);

    List<DfetlPrecheckExport> findByRunIdOrderByCreatedAtDesc(Long runId);
}
