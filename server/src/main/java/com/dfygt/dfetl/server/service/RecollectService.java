package com.dfygt.dfetl.server.service;

import com.dfygt.dfetl.server.entity.SyncTask;
import com.dfygt.dfetl.server.repository.SyncTaskRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Locale;
import java.util.NoSuchElementException;

/** 重采用例编排：预检和队列预留必须先于任何目标表破坏操作。 */
@Service
@RequiredArgsConstructor
public class RecollectService {

    private final SyncTaskRepository syncTaskRepository;
    private final RecollectPreflightService preflightService;
    private final RecollectTargetCleaner targetCleaner;
    private final TaskExecutionQueue executionQueue;

    public RecollectResult recollect(Long taskId, String mode) {
        String normalizedMode = normalizeMode(mode);
        SyncTask task = syncTaskRepository.findById(taskId)
                .orElseThrow(() -> new NoSuchElementException("SyncTask not found: " + taskId));

        try (TaskExecutionQueue.SubmissionReservation reservation =
                     executionQueue.reserveDestructive(taskId)) {
            preflightService.assertReady(task);
            int cleared = targetCleaner.clear(task, normalizedMode);
            task.setIncrementalCheckpoint(null);
            task.setInitialFullSyncDone(false);
            syncTaskRepository.save(task);
            reservation.submit("RECOLLECT_" + normalizedMode);
            return new RecollectResult(taskId, normalizedMode, "submitted", cleared);
        }
    }

    private String normalizeMode(String mode) {
        String normalized = mode == null ? "TRUNCATE" : mode.trim().toUpperCase(Locale.ROOT);
        if (!"TRUNCATE".equals(normalized) && !"DROP_RECREATE".equals(normalized)) {
            throw new IllegalArgumentException("recollect mode 仅支持 TRUNCATE / DROP_RECREATE");
        }
        return normalized;
    }

    public record RecollectResult(Long taskId, String mode, String status, int tablesCleared) { }
}
