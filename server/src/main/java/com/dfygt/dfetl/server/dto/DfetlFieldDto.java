package com.dfygt.dfetl.server.dto;

import lombok.Data;

import java.time.Instant;

/** 只读标准字段快照。 */
@Data
public class DfetlFieldDto {
    private Long id;
    private Long datasetId;
    private String medicalFieldId;
    private String fieldCode;
    private String targetFieldCode;
    private String fieldName;
    private Integer fieldOrder;
    private String standardType;
    private String standardFormat;
    private String dorisType;
    private Boolean primaryKey;
    private Boolean requiredByStandard;
    private String valueDomainCode;
    private String standardVersion;
    private String fieldStatus;
    private Instant createdAt;
    private Instant updatedAt;
}
