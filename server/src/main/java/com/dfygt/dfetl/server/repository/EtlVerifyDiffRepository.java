package com.dfygt.dfetl.server.repository;

import com.dfygt.dfetl.server.entity.EtlVerifyDiff;
import jakarta.persistence.QueryHint;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

public interface EtlVerifyDiffRepository extends JpaRepository<EtlVerifyDiff, Long> {

    Page<EtlVerifyDiff> findByTaskIdAndExecId(Long taskId, Long execId, Pageable pageable);

    Page<EtlVerifyDiff> findByTaskIdAndValidationRunId(Long taskId, Long validationRunId, Pageable pageable);

    Page<EtlVerifyDiff> findByTaskIdAndExecIdAndDiffType(Long taskId, Long execId, String diffType, Pageable pageable);

    Page<EtlVerifyDiff> findByTaskIdAndValidationRunIdAndDiffType(Long taskId, Long validationRunId, String diffType, Pageable pageable);

    long countByTaskIdAndValidationRunId(Long taskId, Long validationRunId);

    /**
     * spec validation-workbench-redesign · P0-1.1：摘要 diffRows 单一权威源。
     *
     * <p>语义：返回 {@code etl_verify_diff} 表中 {@code validation_run_id == :runId} 的全部行数，
     * 涵盖 5 种 diffType（INSERT_MISSING / UPDATE_DIFF / DELETE_MISSING / ROW_AUDIT_MISSING /
     * ROW_AUDIT_MISMATCH），不区分 taskId 维度（同一 runId 物理上只属于一个 task）。
     *
     * <p>使用 {@code jakarta.persistence.query.timeout} 提示驱动在 5000ms 内未返回时抛出
     * {@code QueryTimeoutException}，由 {@link com.dfygt.dfetl.server.service.ValidationGoalSummaryService}
     * 捕获并降级为带错误码的失败摘要响应、不得以 0 静默掩盖。
     */
    @QueryHints({
            @QueryHint(name = "jakarta.persistence.query.timeout", value = "5000")
    })
    long countByValidationRunId(Long validationRunId);

    long countByExecIdAndDiffType(Long execId, String diffType);

    /** P2-1：按 validationRunId + repairStatus 统计差异行数 */
    long countByValidationRunIdAndRepairStatus(Long validationRunId, String repairStatus);

    /**
     * spec validation-workbench-redesign · Task P1-3.1：按 diffType 分组计数。
     *
     * <p>返回 List&lt;Object[]&gt;，每行是 {@code [diffType, count]}；调用方在 Service 层折叠为
     * INSERT_MISSING_COUNT / UPDATE_DIFF_COUNT / DELETE_MISSING_COUNT 三类业务故事。
     *
     * <p>同一只读事务内复用 P0-1.1 的 5000ms 查询超时（READ_COMMITTED 隔离级别由调用方设置）。
     */
    @QueryHints({
            @QueryHint(name = "jakarta.persistence.query.timeout", value = "5000")
    })
    @Query("SELECT d.diffType, COUNT(d) FROM EtlVerifyDiff d "
            + "WHERE d.validationRunId = :runId GROUP BY d.diffType")
    List<Object[]> countGroupByDiffTypeForRun(@Param("runId") Long runId);

    @Query("SELECT d.diffType, COUNT(d) FROM EtlVerifyDiff d "
            + "WHERE d.validationRunId = :runId AND d.repairStatus = :repairStatus "
            + "GROUP BY d.diffType")
    List<Object[]> countGroupByDiffTypeAndRepairStatusForRun(@Param("runId") Long runId,
                                                             @Param("repairStatus") String repairStatus);

    // ── spec 024：Repair 引擎所需 ────────────────────────────────────────
    @Query("SELECT d FROM EtlVerifyDiff d WHERE d.taskId=:taskId AND d.execId=:execId AND d.repairStatus='PENDING' ORDER BY d.id ASC")
    List<EtlVerifyDiff> findPending(@Param("taskId") Long taskId, @Param("execId") Long execId);

    @Query("SELECT d FROM EtlVerifyDiff d WHERE d.validationRunId=:validationRunId AND d.repairStatus='PENDING' ORDER BY d.id ASC")
    List<EtlVerifyDiff> findPendingByValidationRunId(@Param("validationRunId") Long validationRunId);

    long countByTaskIdAndExecIdAndRepairStatus(Long taskId, Long execId, String repairStatus);

    @Transactional
    @Modifying
    @Query("DELETE FROM EtlVerifyDiff d WHERE d.taskId = :taskId")
    void deleteByTaskId(@Param("taskId") Long taskId);

    @Transactional
    @Modifying
    @Query("DELETE FROM EtlVerifyDiff d WHERE d.validationRunId = :validationRunId")
    void deleteByValidationRunId(@Param("validationRunId") Long validationRunId);

    @Transactional
    @Modifying
    @Query("DELETE FROM EtlVerifyDiff d WHERE d.validationRunId = :runId AND d.repairStatus = :status")
    void deleteByValidationRunIdAndRepairStatus(@Param("runId") Long validationRunId, @Param("status") String repairStatus);

    /**
     * spec validation-workbench-redesign Phase 2：历史比对（diff-of-diffs）。
     *
     * <p>查询某次 run 的所有 pkValue 集合（用于与另一次 run 做差集比对）。
     */
    @Query("SELECT d.pkValue FROM EtlVerifyDiff d WHERE d.validationRunId = :runId")
    List<String> findPkValuesByValidationRunId(@Param("runId") Long runId);

    /**
     * spec validation-workbench-redesign Phase 2：按 runId + pkValue 列表查差异行。
     */
    @Query("SELECT d FROM EtlVerifyDiff d WHERE d.validationRunId = :runId AND d.pkValue IN :pkValues ORDER BY d.id ASC")
    List<EtlVerifyDiff> findByValidationRunIdAndPkValueIn(@Param("runId") Long runId, @Param("pkValues") List<String> pkValues);
}
