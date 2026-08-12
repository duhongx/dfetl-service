package com.dfygt.dfetl.server.dto;

import com.dfygt.dfetl.server.entity.DfetlPrecheckRun;

import java.time.Instant;

public record DfetlPrecheckRunDto(
        Long id,
        Long routeId,
        Long datasetId,
        Long institutionId,
        Long taskId,
        Long executionId,
        Long retryOfRunId,
        String runType,
        String scopeType,
        Instant windowStart,
        Instant windowEnd,
        Long windowStartId,
        Long windowEndId,
        String contractHash,
        Long routeRevision,
        String targetSchemaHash,
        String status,
        String stage,
        Short progressPercent,
        String engineJobId,
        String stagingTable,
        Long sourceRows,
        Long loadedRows,
        Long checkedRows,
        Long issueCount,
        Long scannedRows,
        Long passedRows,
        Long blockerRows,
        Long warningRows,
        Long fixedIssueRows,
        String errorMessage,
        Instant startedAt,
        Instant finishedAt,
        Instant createdAt) {

    public static DfetlPrecheckRunDto from(DfetlPrecheckRun run) {
        return new DfetlPrecheckRunDto(
                run.getId(), run.getRouteId(), run.getDatasetId(), run.getInstitutionId(),
                run.getTaskId(), run.getExecutionId(), run.getRetryOfRunId(), run.getRunType(),
                run.getScopeType(), run.getWindowStart(), run.getWindowEnd(), run.getWindowStartId(),
                run.getWindowEndId(), run.getContractHash(), run.getRouteRevision(),
                run.getTargetSchemaHash(), run.getStatus(), run.getStage(), run.getProgressPercent(),
                run.getEngineJobId(), run.getStagingTable(), run.getSourceRows(), run.getLoadedRows(),
                run.getCheckedRows(), run.getIssueCount(),
                run.getScannedRows(), run.getPassedRows(), run.getBlockerRows(), run.getWarningRows(),
                run.getFixedIssueRows(), run.getErrorMessage(), run.getStartedAt(), run.getFinishedAt(),
                run.getCreatedAt());
    }
}
