package com.dfygt.dfetl.server.repository;

import com.dfygt.dfetl.server.entity.TaskExecution;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface TaskExecutionRepository extends JpaRepository<TaskExecution, Long> {

    Page<TaskExecution> findByTaskId(Long taskId, Pageable pageable);

    Page<TaskExecution> findByStatus(String status, Pageable pageable);

    Page<TaskExecution> findByStatusAndReconcileHandled(String status, Boolean reconcileHandled, Pageable pageable);

    List<TaskExecution> findByTaskIdAndStatus(Long taskId, String status);

    Optional<TaskExecution> findTopByTaskIdOrderByIdDesc(Long taskId);

    Optional<TaskExecution> findTopByTaskIdAndStatusOrderByIdDesc(Long taskId, String status);

    /**
     * 取消协调器使用的最新可取消 execution。悲观锁保证任务级、execution 级取消不会并发改写同一终态。
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<TaskExecution> findTopByTaskIdAndStatusInOrderByIdDesc(
            Long taskId, Collection<String> statuses);

    /**
     * 按 execution id 获取取消写锁，避免两个取消入口相互覆盖终态。
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT e FROM TaskExecution e WHERE e.id = :id")
    Optional<TaskExecution> findByIdForCancellation(@Param("id") Long id);

    @Query("SELECT e.id FROM TaskExecution e WHERE e.taskId = :taskId")
    List<Long> findIdByTaskId(@Param("taskId") Long taskId);

    @Modifying
    @Transactional
    @Query("DELETE FROM TaskExecution e WHERE e.taskId = :taskId")
    void deleteByTaskId(@Param("taskId") Long taskId);

    List<TaskExecution> findByStatus(String status);

    long countByStatus(String status);

    long countByStatusAndReconcileHandled(String status, Boolean reconcileHandled);

    long countByTaskId(Long taskId);

    long countByTaskIdAndStatus(Long taskId, String status);

    long countByTaskIdAndStatusAndReconcileHandled(Long taskId, String status, Boolean reconcileHandled);

    @Query("SELECT COUNT(e) FROM TaskExecution e WHERE e.createdAt >= :since")
    long countByCreatedAtAfter(@Param("since") LocalDateTime since);

    @Query("SELECT COUNT(e) FROM TaskExecution e WHERE e.createdAt >= :since AND e.status = :status")
    long countByCreatedAtAfterAndStatus(@Param("since") LocalDateTime since, @Param("status") String status);

    /**
     * 启动时将残留的 RUNNING/PENDING 状态标记为 INTERRUPTED。
     */
    @Modifying
    @Transactional
    @Query("UPDATE TaskExecution e SET e.status = 'INTERRUPTED', e.finishedAt = :finishedAt WHERE e.status IN ('RUNNING', 'PENDING')")
    int markStaleAsInterrupted(@Param("finishedAt") Instant finishedAt);

    /**
     * 查询指定状态集合的 TaskExecution 记录（启动恢复用）。
     */
    List<TaskExecution> findByStatusIn(List<String> statuses);
}
