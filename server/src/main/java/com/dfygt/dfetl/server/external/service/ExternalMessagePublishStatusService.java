package com.dfygt.dfetl.server.external.service;

import com.dfygt.dfetl.server.common.ExecutionErrorSanitizer;
import com.dfygt.dfetl.server.config.MessagePublishProperties;
import com.dfygt.dfetl.server.dto.MessagePublishConfigDto;
import com.dfygt.dfetl.server.entity.MessagePublishLog;
import com.dfygt.dfetl.server.entity.Institution;
import com.dfygt.dfetl.server.entity.SyncTask;
import com.dfygt.dfetl.server.entity.TaskExecution;
import com.dfygt.dfetl.server.external.dto.ExternalMessagePublishStatusResponse;
import com.dfygt.dfetl.server.external.security.ExternalApiAuthorizationService;
import com.dfygt.dfetl.server.external.security.ExternalApiSecurityContext;
import com.dfygt.dfetl.server.repository.InstitutionRepository;
import com.dfygt.dfetl.server.repository.MessagePublishLogRepository;
import com.dfygt.dfetl.server.repository.MessageSendRecordRepository;
import com.dfygt.dfetl.server.repository.SyncTaskRepository;
import com.dfygt.dfetl.server.repository.TaskExecutionRepository;
import com.dfygt.dfetl.server.service.publish.MessagePublishConfigService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class ExternalMessagePublishStatusService {

    private final TaskExecutionRepository executionRepository;
    private final MessagePublishLogRepository logRepository;
    private final MessageSendRecordRepository sendRecordRepository;
    private final MessagePublishConfigService configService;
    private final MessagePublishProperties properties;
    private final SyncTaskRepository taskRepository;
    private final InstitutionRepository institutionRepository;
    private final ExternalApiAuthorizationService authorizationService;

    @Transactional(readOnly = true)
    public ExternalMessagePublishStatusResponse get(Long executionId) {
        if (executionId == null || executionId <= 0) {
            throw new IllegalArgumentException("executionId 必须为正整数");
        }
        TaskExecution execution = executionRepository.findById(executionId)
                .orElseThrow(() -> new NoSuchElementException(
                        "TaskExecution not found: " + executionId));
        assertTaskAllowed(execution.getTaskId());
        MessagePublishConfigDto config = configService.getConfig(execution.getTaskId()).orElse(null);
        Optional<MessagePublishLog> latest = logRepository
                .findTopByTaskIdAndBatchIdInOrderByPublishTimeDesc(
                        execution.getTaskId(), List.of(executionId, -executionId));
        MessagePublishLog publish = latest.orElse(null);
        long confirmed = publish == null ? 0
                : sendRecordRepository.countByPublishLogIdAndSendStatus(publish.getId(), "SENT");
        long failed = publish == null ? 0
                : sendRecordRepository.countByPublishLogIdAndSendStatus(publish.getId(), "SEND_FAILED");
        String publishStatus = publish == null ? "PENDING" : publish.getStatus();
        String workflowStatus = workflowStatus(execution.getStatus(), publishStatus, publish != null);
        boolean timedOutPending = publish != null
                && "PENDING".equalsIgnoreCase(publishStatus)
                && publish.getPublishTime() != null
                && publish.getPublishTime().isBefore(java.time.Instant.now().minusSeconds(
                properties.getPendingRetryTimeoutSeconds()));
        boolean retryable = "SUCCESS".equalsIgnoreCase(execution.getStatus())
                && ("FAILED".equalsIgnoreCase(publishStatus)
                || "PARTIAL".equalsIgnoreCase(publishStatus)
                || timedOutPending);
        String error = publish != null && publish.getErrorMessage() != null
                ? publish.getErrorMessage()
                : execution.getErrorMsg();
        return new ExternalMessagePublishStatusResponse(
                execution.getTaskId(),
                execution.getId(),
                publish == null ? null : publish.getId(),
                workflowStatus,
                execution.getStatus(),
                publishStatus,
                execution.getWriteRows(),
                confirmed,
                failed,
                properties.getTransport().name(),
                config == null ? null : config.getFullSyncMode(),
                retryable,
                ExecutionErrorSanitizer.sanitize(error));
    }

    private static String workflowStatus(String syncStatus, String publishStatus,
                                         boolean publishExists) {
        if ("PENDING".equalsIgnoreCase(syncStatus)) return "SYNC_PENDING";
        if ("RUNNING".equalsIgnoreCase(syncStatus)) return "SYNC_RUNNING";
        if (!"SUCCESS".equalsIgnoreCase(syncStatus)) return "SYNC_FAILED";
        if (!publishExists || "PENDING".equalsIgnoreCase(publishStatus)) return "MESSAGE_PENDING";
        if ("RUNNING".equalsIgnoreCase(publishStatus)) return "MESSAGE_RUNNING";
        if ("SUCCESS".equalsIgnoreCase(publishStatus)) return "SUCCESS";
        if ("PARTIAL".equalsIgnoreCase(publishStatus)) return "MESSAGE_PARTIAL";
        if ("FAILED".equalsIgnoreCase(publishStatus)) return "MESSAGE_FAILED";
        if ("SKIPPED".equalsIgnoreCase(publishStatus)) return "MESSAGE_SKIPPED";
        return "MESSAGE_UNKNOWN";
    }

    private void assertTaskAllowed(Long taskId) {
        if (ExternalApiSecurityContext.current().isEmpty()) {
            return;
        }
        SyncTask task = taskRepository.findById(taskId)
                .orElseThrow(() -> denied("同步任务不存在或不可访问: " + taskId));
        if (task.getInstitutionId() == null) {
            throw denied("同步任务未关联医疗机构: " + taskId);
        }
        Institution institution = institutionRepository.findById(task.getInstitutionId())
                .orElseThrow(() -> denied("同步任务关联机构不存在: " + task.getInstitutionId()));
        authorizationService.assertAllowed(institution.getCode());
    }

    private static org.springframework.security.access.AccessDeniedException denied(String message) {
        return new org.springframework.security.access.AccessDeniedException(message);
    }
}
