package com.dfygt.dfetl.server.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.time.LocalDateTime;

@Getter
@AllArgsConstructor
public class SnapshotExecutionDto {
    private Long executionId;
    private LocalDateTime capturedAt;
    private Long keyCount;
    private String source;
}
