package com.dfygt.dfetl.server.repository;

import com.dfygt.dfetl.server.entity.TaskValidationConfig;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

public interface TaskValidationConfigRepository extends JpaRepository<TaskValidationConfig, Long> {

    Optional<TaskValidationConfig> findByTaskId(Long taskId);

    boolean existsByTaskId(Long taskId);

    @Modifying
    @Transactional
    @Query("DELETE FROM TaskValidationConfig c WHERE c.taskId = :taskId")
    void deleteByTaskId(@Param("taskId") Long taskId);
}
