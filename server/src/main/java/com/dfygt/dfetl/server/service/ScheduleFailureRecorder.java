package com.dfygt.dfetl.server.service;

import com.dfygt.dfetl.server.entity.SyncTask;
import com.dfygt.dfetl.server.repository.SyncTaskRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.NoSuchElementException;

/**
 * 在 Quartz AFTER_COMMIT 回调失败时，以独立事务持久化任务调度失败状态。
 */
@Service
@RequiredArgsConstructor
public class ScheduleFailureRecorder {

    private final SyncTaskRepository repository;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public SyncTask markFailed(Long taskId, String scheduleType, String errorMessage) {
        SyncTask task = repository.findById(taskId)
                .orElseThrow(() -> new NoSuchElementException("SyncTask not found: " + taskId));
        task.setStatus("FAILED");
        task.setLastRunStatus("SCHEDULE_FAILED");
        task.setAlertStatus("ERROR");
        return repository.saveAndFlush(task);
    }
}
