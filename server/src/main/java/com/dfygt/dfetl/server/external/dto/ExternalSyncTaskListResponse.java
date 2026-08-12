package com.dfygt.dfetl.server.external.dto;

import java.util.List;

/** 外部标准数据集任务批量规划或创建结果。 */
public record ExternalSyncTaskListResponse(
        String requestId,
        String status,
        int totalCount,
        int createdCount,
        int runSubmittedCount,
        int taskExistsCount,
        int blockedCount,
        int runFailedCount,
        boolean idempotent,
        List<ExternalDatasetTaskItemResponse> items) {
}
