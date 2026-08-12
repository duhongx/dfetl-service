package com.dfygt.dfetl.server.repository;

import com.dfygt.dfetl.server.entity.SnapshotApplyHistory;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface SnapshotApplyHistoryRepository extends JpaRepository<SnapshotApplyHistory, Long> {
    List<SnapshotApplyHistory> findTop20ByTaskIdOrderByCreatedAtDesc(Long taskId);

    /** spec：取最近一次 apply 记录，用于工作台 DELETE 摘要卡片快速渲染（避免实时跑大快照差集） */
    Optional<SnapshotApplyHistory> findFirstByTaskIdOrderByCreatedAtDesc(Long taskId);
}
