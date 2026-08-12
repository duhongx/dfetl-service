package com.dfygt.dfetl.server.medical.quality;

import java.util.List;

public record MedicalDirtyRowIssue(
        Long taskId,
        Long executionId,
        String datasetCode,
        String datasetName,
        String sourceSchema,
        String sourceView,
        String targetTable,
        String businessPkJson,
        String sourceRowHash,
        String windowJson,
        String ownerName,
        String ownerSource,
        String rowAction,
        String severity,
        String rawRowJson,
        List<MedicalDirtyFieldIssue> fields
) {}
