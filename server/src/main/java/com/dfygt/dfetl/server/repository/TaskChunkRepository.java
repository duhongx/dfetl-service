package com.dfygt.dfetl.server.repository;

import com.dfygt.dfetl.server.entity.TaskChunk;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

public interface TaskChunkRepository extends JpaRepository<TaskChunk, Long> {

    List<TaskChunk> findByExecutionIdOrderByChunkNoAsc(Long executionId);

    @Modifying
    @Transactional
    @Query("DELETE FROM TaskChunk c WHERE c.executionId IN :ids")
    void deleteByExecutionIdIn(@Param("ids") List<Long> ids);

    long countByExecutionIdAndStatus(Long executionId, String status);

    long countByExecutionId(Long executionId);
}
