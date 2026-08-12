package com.dfygt.dfetl.server.dto;

/** 数据预检工作台中的一个 PostgreSQL 视图核对对象。 */
public record DfetlPrecheckWorkspaceRowDto(
        Long routeId,
        Long institutionId,
        String institutionCode,
        String institutionName,
        Long datasetId,
        String datasetCode,
        String datasetName,
        Long sourceDatasourceId,
        String sourceDatasourceName,
        String sourceSchema,
        String sourceObject,
        String validationStatus,
        String validationSummary,
        String validationDetailsJson,
        String structureInvalidReason,
        String workspaceStatus,
        DfetlPrecheckRunDto latestRun) {
}
