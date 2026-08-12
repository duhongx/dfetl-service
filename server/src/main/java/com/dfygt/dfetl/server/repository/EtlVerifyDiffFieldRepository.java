package com.dfygt.dfetl.server.repository;

import com.dfygt.dfetl.server.entity.EtlVerifyDiffField;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Spec 056：字段级差异预计算结果仓库。
 */
public interface EtlVerifyDiffFieldRepository extends JpaRepository<EtlVerifyDiffField, Long> {

    /** cache-first 路径用：按 diffId 拿全部字段差异（按 id 升序保留预计算时的列序）。 */
    List<EtlVerifyDiffField> findByDiffIdOrderByIdAsc(Long diffId);

    /** CSV 导出用：按 (taskId, execId) 流式分页（这里直接全取，调用方负责限量）。 */
    List<EtlVerifyDiffField> findByTaskIdAndExecIdOrderByDiffIdAscIdAsc(Long taskId, Long execId);

    /** CSV 导出分页用：按 diffId 列表批量查询字段差异。 */
    List<EtlVerifyDiffField> findByDiffIdInOrderByDiffIdAscIdAsc(List<Long> diffIds);

    List<EtlVerifyDiffField> findByValidationRunIdOrderByDiffIdAscIdAsc(Long validationRunId);

    /** 幂等：重新预计算前先按 execId 清空旧数据。 */
    @Modifying
    @Transactional
    @Query("DELETE FROM EtlVerifyDiffField f WHERE f.execId = :execId")
    int deleteByExecId(@Param("execId") Long execId);

    /** 幂等：重新预计算前只清空当前 ValidationRun 的字段级差异缓存。 */
    @Modifying
    @Transactional
    @Query("DELETE FROM EtlVerifyDiffField f WHERE f.validationRunId = :validationRunId")
    int deleteByValidationRunId(@Param("validationRunId") Long validationRunId);

    /**
     * spec validation-workbench-redesign Phase 3：字段映射变更时失效该 task 的所有缓存。
     */
    @Transactional
    @Modifying
    @Query("DELETE FROM EtlVerifyDiffField f WHERE f.taskId = :taskId")
    int deleteByTaskId(@Param("taskId") Long taskId);

    long countByExecId(Long execId);
}
