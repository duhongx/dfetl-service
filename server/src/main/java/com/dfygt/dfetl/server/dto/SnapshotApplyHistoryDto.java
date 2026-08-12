package com.dfygt.dfetl.server.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.time.LocalDateTime;

@Getter
@AllArgsConstructor
public class SnapshotApplyHistoryDto {
    private Long id;
    private Long prevExecutionId;
    private Long currExecutionId;
    private boolean dryRun;
    private int detectedKeys;
    private Long loadedRows;
    private Long filteredRows;
    private String result;
    private String message;
    private LocalDateTime createdAt;
}
