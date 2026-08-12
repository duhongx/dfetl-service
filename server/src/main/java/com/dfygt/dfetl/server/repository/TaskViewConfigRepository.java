package com.dfygt.dfetl.server.repository;

import com.dfygt.dfetl.server.entity.TaskViewConfig;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

public interface TaskViewConfigRepository extends JpaRepository<TaskViewConfig, Long> {

    List<TaskViewConfig> findByTaskId(Long taskId);

    Optional<TaskViewConfig> findByTaskIdAndViewName(Long taskId, String viewName);

    @Modifying
    @Transactional
    @Query("DELETE FROM TaskViewConfig v WHERE v.taskId = :taskId")
    void deleteByTaskId(@Param("taskId") Long taskId);
}
