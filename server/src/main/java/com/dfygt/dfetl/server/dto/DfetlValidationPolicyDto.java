package com.dfygt.dfetl.server.dto;

import lombok.Data;

import java.math.BigDecimal;
import java.time.Instant;

@Data
public class DfetlValidationPolicyDto {
    private Long id;
    private Long datasetId;
    private Boolean inheritGlobal;
    private Boolean enabled;
    private String triggerMode;
    private String validationMethod;
    private BigDecimal rowTolerance;
    private Boolean failBlock;
    private Boolean revalidateEnabled;
    private Integer revalidateDelay;
    private Integer lookbackHours;
    private Long policyRevision;
    private Long rowVersion;
    private Instant createdAt;
    private Instant updatedAt;
}
