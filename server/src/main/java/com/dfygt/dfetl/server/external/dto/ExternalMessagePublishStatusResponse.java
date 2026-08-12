package com.dfygt.dfetl.server.external.dto;

/**
 * 外部同步 execution 与消息发布尝试的组合状态。
 */
public record ExternalMessagePublishStatusResponse(
        Long taskId,
        Long executionId,
        Long publishLogId,
        String workflowStatus,
        String syncStatus,
        String publishStatus,
        Long expectedMessages,
        Long confirmedMessages,
        Long failedMessages,
        String transport,
        String fullSyncMode,
        boolean retryable,
        String errorMessage
) {
}
