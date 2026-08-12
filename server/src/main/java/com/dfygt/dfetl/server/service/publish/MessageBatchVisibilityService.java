package com.dfygt.dfetl.server.service.publish;

import com.dfygt.dfetl.server.config.MessagePublishProperties;
import com.dfygt.dfetl.server.entity.TaskExecution;
import com.dfygt.dfetl.server.repository.TaskExecutionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** 同步成功后确认 execution 对应的 Doris 批次已可供消息发布读取。 */
@Service
@RequiredArgsConstructor
public class MessageBatchVisibilityService {

    private final ChangeDataReader changeDataReader;
    private final TaskExecutionRepository executionRepository;
    private final MessagePublishProperties properties;

    public long awaitVisibleRows(Long targetDsId, String targetTable, Long taskId, Long executionId) {
        TaskExecution execution = executionRepository.findById(executionId)
                .orElseThrow(() -> new MessageBatchNotVisibleException(
                        "Cannot verify Doris batch visibility: executionId=" + executionId + " not found"));
        long expectedRows = expectedRows(execution);
        long timeoutMs = Math.max(0L, properties.getDorisVisibilityTimeoutMs());
        long deadlineNanos = System.nanoTime() + timeoutMs * 1_000_000L;

        do {
            long visibleRows = changeDataReader.countByBatch(
                    targetDsId, targetTable, taskId, executionId);
            if (visibleRows > 0 || expectedRows == 0) {
                return visibleRows;
            }
            if (System.nanoTime() >= deadlineNanos) {
                break;
            }
            sleepUntilNextPoll();
        } while (true);

        throw new MessageBatchNotVisibleException(
                "Doris batch is not visible before timeout: executionId=" + executionId
                        + ", taskId=" + taskId + ", expectedRows=" + expectedRows);
    }

    private long expectedRows(TaskExecution execution) {
        if (execution.getValidSourceRows() != null) {
            return Math.max(0L, execution.getValidSourceRows());
        }
        if (execution.getWriteRows() != null) {
            return Math.max(0L, execution.getWriteRows());
        }
        if (execution.getEngineWriteRows() != null) {
            return Math.max(0L, execution.getEngineWriteRows());
        }
        throw new MessageBatchNotVisibleException(
                "Cannot verify Doris batch visibility: executionId=" + execution.getId()
                        + " has no row-count evidence");
    }

    private void sleepUntilNextPoll() {
        try {
            Thread.sleep(Math.max(1L, properties.getDorisVisibilityPollIntervalMs()));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new MessageBatchNotVisibleException("Doris batch visibility check interrupted", e);
        }
    }
}
