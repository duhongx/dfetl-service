package com.dfygt.dfetl.server.repository;

import com.dfygt.dfetl.server.entity.EtlVerifyChunk;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

public interface EtlVerifyChunkRepository extends JpaRepository<EtlVerifyChunk, Long> {

    List<EtlVerifyChunk> findByExecIdOrderByChunkNoAsc(Long execId);

    List<EtlVerifyChunk> findByTaskIdAndExecIdOrderByChunkNoAsc(Long taskId, Long execId);

    Page<EtlVerifyChunk> findByTaskIdAndExecId(Long taskId, Long execId, Pageable pageable);

    List<EtlVerifyChunk> findByValidationRunIdOrderByChunkNoAsc(Long validationRunId);

    long countByValidationRunId(Long validationRunId);

    long countByValidationRunIdAndMatchedTrue(Long validationRunId);

    boolean existsByValidationRunIdAndMatchedFalse(Long validationRunId);

    Page<EtlVerifyChunk> findByTaskIdAndValidationRunId(Long taskId, Long validationRunId, Pageable pageable);

    List<EtlVerifyChunk> findByTaskIdOrderByExecIdDescChunkNoAsc(Long taskId);

    Page<EtlVerifyChunk> findByTaskId(Long taskId, Pageable pageable);

    /** Spec 032：返回某次 verify 中所有 matched=true 的 chunkNo，用于断点续跑跳过。 */
    @Query("select c.chunkNo from EtlVerifyChunk c where c.execId = :execId and c.matched = true")
    List<Integer> findMatchedChunkNosByExecId(@Param("execId") Long execId);

    /** Spec 032：根据 (execId, chunkNo) 取已完成的分片快照，用于复制到新 execId。 */
    List<EtlVerifyChunk> findByExecIdAndMatchedTrue(Long execId);

    /** Spec 032：断点续跑必须按 taskId 限定 legacy execId，避免手工 execId=0/-N 跨任务串读。 */
    List<EtlVerifyChunk> findByTaskIdAndExecIdAndMatchedTrue(Long taskId, Long execId);

    /** 重新校验前清除同 execId 的旧分片记录，避免唯一键冲突。 */
    @Transactional
    @Modifying
    @Query("DELETE FROM EtlVerifyChunk c WHERE c.execId = :execId")
    void deleteByExecId(@Param("execId") Long execId);

    /** 重新校验前清除同 ValidationRun 的旧分片记录；execId 可复用但 runId 不复用。 */
    @Transactional
    @Modifying
    @Query("DELETE FROM EtlVerifyChunk c WHERE c.validationRunId = :validationRunId")
    void deleteByValidationRunId(@Param("validationRunId") Long validationRunId);

    /** 测试任务/任务删除清理：按 taskId 清理所有分片记录。 */
    @Transactional
    @Modifying
    @Query("DELETE FROM EtlVerifyChunk c WHERE c.taskId = :taskId")
    void deleteByTaskId(@Param("taskId") Long taskId);
}
