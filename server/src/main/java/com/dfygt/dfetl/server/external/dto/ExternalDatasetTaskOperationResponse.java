package com.dfygt.dfetl.server.external.dto;

/** 外部任务身份操作结果，不暴露 DFETL 内部连接或路由 ID。 */
public record ExternalDatasetTaskOperationResponse(
        String yiLiaoJgDm,
        String datasetCode,
        String status,
        Long taskId,
        String taskName,
        String taskStatus,
        String message) {
}
