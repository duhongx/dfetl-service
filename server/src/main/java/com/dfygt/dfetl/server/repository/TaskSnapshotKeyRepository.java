package com.dfygt.dfetl.server.repository;

import com.dfygt.dfetl.server.entity.TaskSnapshotKey;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;

public interface TaskSnapshotKeyRepository extends JpaRepository<TaskSnapshotKey, Long> {

    @Query("SELECT k.keyValue FROM TaskSnapshotKey k WHERE k.taskId = :taskId AND k.executionId = :executionId")
    List<String> findKeyValues(@Param("taskId") Long taskId, @Param("executionId") Long executionId);

    @Query("SELECT DISTINCT k.executionId FROM TaskSnapshotKey k WHERE k.taskId = :taskId ORDER BY k.executionId DESC")
    List<Long> findExecutionIdsDesc(@Param("taskId") Long taskId);

    interface SnapshotExecutionRow {
        Long getExecutionId();
        LocalDateTime getCapturedAt();
        Long getKeyCount();
    }

    @Query("""
            SELECT k.executionId AS executionId, MAX(k.capturedAt) AS capturedAt, COUNT(k.id) AS keyCount
            FROM TaskSnapshotKey k
            WHERE k.taskId = :taskId
            GROUP BY k.executionId
            ORDER BY k.executionId DESC
            """)
    List<SnapshotExecutionRow> findExecutionSummaries(@Param("taskId") Long taskId);

    /** spec 036：取任务上次 capture 时间，用于频控 */
    @Query("SELECT MAX(k.capturedAt) FROM TaskSnapshotKey k WHERE k.taskId = :taskId")
    java.time.LocalDateTime findMaxCapturedAt(@Param("taskId") Long taskId);

    /**
     * P0-1 / P2-15: 数据库层 COUNT 差集，避免内存 OOM。
     *
     * <p>原实现用 {@code LOWER(k1.keyValue) NOT IN (SELECT LOWER(k2.keyValue) ...)}，
     * 在大快照（10 万级）+ 写事务并发场景下会卡住（PG 走 hashed SubPlan，但 LOWER 函数转换 + 并发锁等待）。
     *
     * <p>P2-15 重写：
     * <ul>
     *   <li>使用 NOT EXISTS 反向相关子查询，让 PG 走基于 (task_id, execution_id) 索引的 anti-join</li>
     *   <li>{@code LOWER(...)} 仍保留以兼容大小写不敏感比较</li>
     * </ul>
     *
     * <p>调用方（{@code ValidationGoalSummaryService.deleteSummary}）应通过 statement_timeout 控制兜底。
     */
    @Query("""
            SELECT COUNT(k1) FROM TaskSnapshotKey k1
            WHERE k1.taskId = :taskId AND k1.executionId = :prevExecId
            AND NOT EXISTS (
                SELECT 1 FROM TaskSnapshotKey k2
                WHERE k2.taskId = :taskId
                  AND k2.executionId = :currExecId
                  AND LOWER(k2.keyValue) = LOWER(k1.keyValue)
            )
            """)
    long countDeletedKeys(@Param("taskId") Long taskId,
                          @Param("prevExecId") Long prevExecId,
                          @Param("currExecId") Long currExecId);

    @Modifying
    @Query("DELETE FROM TaskSnapshotKey k WHERE k.taskId = :taskId AND k.executionId IN :executionIds")
    int deleteByTaskAndExecutions(@Param("taskId") Long taskId, @Param("executionIds") List<Long> executionIds);

    @Modifying
    @Query("DELETE FROM TaskSnapshotKey k WHERE k.taskId = :taskId AND k.executionId = :executionId")
    int deleteByTaskAndExecution(@Param("taskId") Long taskId, @Param("executionId") Long executionId);

    @Modifying
    @Query("DELETE FROM TaskSnapshotKey k WHERE k.taskId = :taskId")
    int deleteByTaskId(@Param("taskId") Long taskId);
}
