package com.dfygt.dfetl.server.external.service;

import com.dfygt.dfetl.server.config.MessagePublishProperties;
import com.dfygt.dfetl.server.dto.MessagePublishConfigDto;
import com.dfygt.dfetl.server.entity.MessagePublishLog;
import com.dfygt.dfetl.server.entity.TaskExecution;
import com.dfygt.dfetl.server.external.dto.ExternalMessagePublishStatusResponse;
import com.dfygt.dfetl.server.repository.MessagePublishLogRepository;
import com.dfygt.dfetl.server.repository.TaskExecutionRepository;
import com.dfygt.dfetl.server.service.publish.MessagePublishConfigService;
import com.dfygt.dfetl.server.service.publish.MessagePublishRunService;
import com.dfygt.dfetl.server.service.publish.MessagePublishTrigger;
import com.dfygt.dfetl.server.service.publish.Transport;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.NoSuchElementException;

@Service
@RequiredArgsConstructor
public class ExternalMessagePublishRetryService {

    private final TaskExecutionRepository executionRepository;
    private final MessagePublishLogRepository logRepository;
    private final MessagePublishConfigService configService;
    private final MessagePublishProperties properties;
    private final MessagePublishRunService runService;
    private final MessagePublishTrigger trigger;
    private final ExternalMessagePublishStatusService statusService;

    public ExternalMessagePublishStatusResponse retry(Long executionId) {
        // 先走组合状态查询的机构授权。
        statusService.get(executionId);
        TaskExecution execution = executionRepository.findById(executionId)
                .orElseThrow(() -> new NoSuchElementException(
                        "TaskExecution not found: " + executionId));
        if (!"SUCCESS".equalsIgnoreCase(execution.getStatus())) {
            throw new IllegalStateException("MESSAGE_RETRY_NOT_ALLOWED: 同步状态为 "
                    + execution.getStatus() + "，只有同步成功后才能重发消息");
        }
        MessagePublishConfigDto config = configService.getConfig(execution.getTaskId())
                .orElseThrow(() -> new IllegalStateException(
                        "MESSAGE_CONFIG_MISSING: 无法重发"));
        if (!config.isEnabled() || !"ALL".equalsIgnoreCase(config.getFullSyncMode())) {
            throw new IllegalStateException(
                    "MESSAGE_CONFIG_MISMATCH: 重发要求 enabled=true 且 fullSyncMode=ALL");
        }
        if (properties.getTransport() != Transport.RABBITMQ) {
            throw new IllegalStateException(
                    "MESSAGE_TRANSPORT_MISMATCH: 重发要求 RABBITMQ");
        }
        MessagePublishLog latest = logRepository
                .findTopByTaskIdAndBatchIdInOrderByPublishTimeDesc(
                        execution.getTaskId(), List.of(executionId, -executionId))
                .orElseThrow(() -> new IllegalStateException(
                        "MESSAGE_PUBLISH_RUN_MISSING: 没有可重发的发布记录"));
        assertRetryable(latest);
        Long retryLogId = runService.prepareRetry(latest, executionId);
        trigger.retryExecution(retryLogId, executionId);
        return new ExternalMessagePublishStatusResponse(
                execution.getTaskId(), executionId, retryLogId,
                "MESSAGE_PENDING", execution.getStatus(), "PENDING",
                execution.getWriteRows(), 0L, 0L,
                properties.getTransport().name(), config.getFullSyncMode(),
                false, null);
    }

    private void assertRetryable(MessagePublishLog latest) {
        String status = latest.getStatus();
        if ("FAILED".equalsIgnoreCase(status) || "PARTIAL".equalsIgnoreCase(status)) {
            return;
        }
        if ("PENDING".equalsIgnoreCase(status)
                && latest.getPublishTime() != null
                && latest.getPublishTime().isBefore(Instant.now().minusSeconds(
                properties.getPendingRetryTimeoutSeconds()))) {
            return;
        }
        throw new IllegalStateException("MESSAGE_RETRY_NOT_ALLOWED: 当前发布状态为 " + status);
    }
}
