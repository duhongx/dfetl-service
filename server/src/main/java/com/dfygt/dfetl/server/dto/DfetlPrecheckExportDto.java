package com.dfygt.dfetl.server.dto;

import com.dfygt.dfetl.server.entity.DfetlPrecheckExport;

import java.time.Instant;

public record DfetlPrecheckExportDto(
        Long id,
        Long runId,
        String format,
        String status,
        Long rowCount,
        Long byteCount,
        String fileManifest,
        String requestedBy,
        String errorMessage,
        Instant expiresAt,
        Instant createdAt,
        Instant finishedAt) {

    public static DfetlPrecheckExportDto from(DfetlPrecheckExport export) {
        return new DfetlPrecheckExportDto(
                export.getId(), export.getRunId(), export.getExportFormat(), export.getStatus(),
                export.getRowCount(), export.getByteCount(), export.getFileManifest(),
                export.getRequestedBy(), export.getErrorMessage(), export.getExpiresAt(),
                export.getCreatedAt(), export.getFinishedAt());
    }
}
