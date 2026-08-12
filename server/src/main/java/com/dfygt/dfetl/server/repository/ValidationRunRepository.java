package com.dfygt.dfetl.server.repository;

import com.dfygt.dfetl.server.entity.ValidationRun;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

public interface ValidationRunRepository extends JpaRepository<ValidationRun, Long> {

    List<ValidationRun> findByTaskIdOrderByIdDesc(Long taskId);

    Optional<ValidationRun> findByIdAndTaskId(Long id, Long taskId);

    Optional<ValidationRun> findByTaskIdAndLegacyExecId(Long taskId, Long legacyExecId);

    @Transactional
    @Modifying
    @Query("DELETE FROM ValidationRun r WHERE r.taskId = :taskId")
    void deleteByTaskId(@Param("taskId") Long taskId);

    /**
     * spec validation-workbench-redesign · Task P1-9.3
     * Validates: Requirement 5 (AC 4) + Property 10
     *
     * <p>查询 AUTO_COUNT 类型且早于 cutoff 的 run id，用于批量归档。
     */
    @Query("SELECT r.id FROM ValidationRun r "
            + "WHERE r.triggerType = 'AUTO_COUNT' AND r.createdAt < :cutoff ORDER BY r.id ASC")
    List<Long> findIdsForAutoCountArchive(@Param("cutoff") java.time.LocalDateTime cutoff,
                                           org.springframework.data.domain.Pageable pageable);

    /**
     * spec validation-workbench-redesign · Task P1-9.3
     * 批量按 id 删除（仅供归档脚本调用）。
     */
    @Transactional
    @Modifying
    @Query("DELETE FROM ValidationRun r WHERE r.id IN :ids")
    int deleteByIds(@Param("ids") List<Long> ids);

    // ══════════════════════════════════════════════════════════════════════════
    //   spec validation-table-consolidation · Step 4
    //   Validates: Requirement 10.3, 10.4, 4.3, 4.4, 6.2
    // ══════════════════════════════════════════════════════════════════════════

    /** 悲观锁查询 RUNNING 状态（并发保护） */
    @Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT r FROM ValidationRun r WHERE r.taskId = :taskId AND r.status = 'RUNNING'")
    java.util.List<ValidationRun> findRunningByTaskIdForUpdate(@Param("taskId") Long taskId);

    /**
     * 列表页：每个 task_id 取最新一条，支持 status/search 联合过滤。
     * 返回 Object[] 其中 [0]=ValidationRun, [1]=SyncTask（LEFT JOIN 无匹配时为 null）。
     *
     * <p>spec validation-table-consolidation · Step 7
     * Validates: Requirement 6.2, 6.3, 6.4, 6.5
     */
    @Query("""
        SELECT r, s FROM ValidationRun r
        LEFT JOIN SyncTask s ON s.id = r.taskId
        WHERE r.id = (SELECT MAX(r2.id) FROM ValidationRun r2 WHERE r2.taskId = r.taskId AND (r2.triggerType IS NULL OR r2.triggerType <> 'AUTO_COUNT'))
          AND (r.triggerType IS NULL OR r.triggerType <> 'AUTO_COUNT')
          AND (:status IS NULL OR :status = '' OR r.status = :status)
          AND (:search IS NULL OR :search = ''
               OR LOWER(r.name) LIKE LOWER(CONCAT('%', :search, '%'))
               OR LOWER(s.name) LIKE LOWER(CONCAT('%', :search, '%')))
        """)
    Page<Object[]> findAllLatestPerTask(@Param("status") String status,
                                         @Param("search") String search,
                                         Pageable pageable);

    /** 按 task_id 查最新一条（用于 by-task/{taskId} 端点） */
    Optional<ValidationRun> findFirstByTaskIdOrderByIdDesc(Long taskId);

    /** 统计 RUNNING 数量（非锁查询，用于快速判断） */
    @Query("SELECT COUNT(r) FROM ValidationRun r WHERE r.taskId = :taskId AND r.status = 'RUNNING'")
    int countRunningByTaskId(@Param("taskId") Long taskId);

    /** 判断某 task_id 是否已有校验记录 */
    boolean existsByTaskId(Long taskId);

    /** 按 executionId 查最新一条（用于 TaskExecutionService 获取校验状态） */
    Optional<ValidationRun> findFirstByExecutionIdOrderByIdDesc(Long executionId);

    /**
     * spec validation-table-consolidation · Step 8
     * 查询某任务最近一条指定 triggerType 的校验结果（用于 GATE 复用窗口内避免重复创建）。
     */
    Optional<ValidationRun> findFirstByTaskIdAndTriggerTypeAndLastRunAtAfterOrderByLastRunAtDesc(
            Long taskId, String triggerType, java.time.Instant lastRunAtAfter);
}
