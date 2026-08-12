package com.dfygt.dfetl.server.dto;

import lombok.Data;

import java.time.Instant;

@Data
public class DfetlSyncPolicyDto {
    private Long id;
    private Long datasetId;
    private String writeMode;
    private String syncTemplate;
    private String incrementalField;
    private String incrementMode;
    private String upperBoundStrategy;
    private Integer upperBoundDelayMinutes;
    private Integer lookbackSeconds;
    private Integer readerParallelism;
    private Integer fetchSize;
    private Integer rateLimit;
    private Boolean scheduleEnabled;
    private String scheduleMode;
    private Integer scheduleIntervalHours;
    private String scheduleCron;
    private String scheduleTimezone;
    private Long policyRevision;
    private Long rowVersion;
    private Instant createdAt;
    private Instant updatedAt;
}
