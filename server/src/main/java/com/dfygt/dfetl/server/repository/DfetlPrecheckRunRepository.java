package com.dfygt.dfetl.server.repository;

import com.dfygt.dfetl.server.entity.DfetlPrecheckRun;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface DfetlPrecheckRunRepository extends JpaRepository<DfetlPrecheckRun, Long> {

    List<DfetlPrecheckRun> findByStatus(String status);
    List<DfetlPrecheckRun> findByStatusInOrderByCreatedAtAsc(List<String> statuses);
    List<DfetlPrecheckRun> findByRouteIdOrderByCreatedAtDesc(Long routeId);
    boolean existsByRouteIdAndContractHashAndRouteRevisionAndStatusIn(
            Long routeId,
            String contractHash,
            Long routeRevision,
            List<String> statuses);
    Optional<DfetlPrecheckRun> findFirstByRouteIdAndRunTypeAndStatusInOrderByCreatedAtDesc(
            Long routeId,
            String runType,
            List<String> statuses);
    Optional<DfetlPrecheckRun> findFirstByRouteIdAndRunTypeOrderByCreatedAtDescIdDesc(
            Long routeId,
            String runType);
    @Query("""
            SELECT run
              FROM DfetlPrecheckRun run
             WHERE run.routeId IN :routeIds
               AND run.runType = 'ROUTE_FULL'
               AND run.id = (
                    SELECT MAX(previous.id)
                      FROM DfetlPrecheckRun previous
                     WHERE previous.routeId = run.routeId
                       AND previous.runType = 'ROUTE_FULL'
               )
            """)
    List<DfetlPrecheckRun> findLatestRouteFullByRouteIds(@Param("routeIds") List<Long> routeIds);
    List<DfetlPrecheckRun> findTop100ByStatusAndRawCleanedAtIsNullAndFinishedAtBeforeOrderByFinishedAtAsc(
            String status,
            Instant finishedBefore);

    /** 原子占用 PENDING 运行，避免两个 Worker 对同一个 run 重复提交 SeaTunnel。 */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query("""
            UPDATE DfetlPrecheckRun run
               SET run.status = 'LOADING',
                   run.stage = 'LOADING',
                   run.progressPercent = 10,
                   run.stagingTable = :stagingTable,
                   run.startedAt = COALESCE(run.startedAt, :startedAt),
                   run.errorMessage = NULL
             WHERE run.id = :runId
               AND run.status = 'PENDING'
            """)
    int claimPendingForLoading(
            @Param("runId") Long runId,
            @Param("startedAt") Instant startedAt,
            @Param("stagingTable") String stagingTable);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query("""
            UPDATE DfetlPrecheckRun run
               SET run.rawCleanedAt = :cleanedAt
             WHERE run.id = :runId
               AND run.status IN ('PASSED', 'HAS_ERRORS')
               AND run.rawCleanedAt IS NULL
            """)
    int markRawCleaned(@Param("runId") Long runId, @Param("cleanedAt") Instant cleanedAt);
}
