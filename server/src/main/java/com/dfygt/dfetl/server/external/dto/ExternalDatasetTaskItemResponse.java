package com.dfygt.dfetl.server.external.dto;

/** 外部批量任务请求中单个标准数据集的处理结果。 */
public record ExternalDatasetTaskItemResponse(
        String datasetCode,
        String status,
        Long taskId,
        String errorCode,
        String message) {
}
