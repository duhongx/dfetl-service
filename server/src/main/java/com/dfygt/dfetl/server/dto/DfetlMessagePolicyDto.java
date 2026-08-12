package com.dfygt.dfetl.server.dto;

import lombok.Data;

import java.time.Instant;

@Data
public class DfetlMessagePolicyDto {
    private Long id;
    private Long datasetId;
    private Boolean enabled;
    private String transport;
    private String fullSyncMode;
    private Integer rateLimit;
    private String routingKey;
    private String topic;
    private String keyTemplate;
    private Integer pageSize;
    private String tenantId;
    private String sourceSystem;
    private Long policyRevision;
    private Long rowVersion;
    private Instant createdAt;
    private Instant updatedAt;
}
