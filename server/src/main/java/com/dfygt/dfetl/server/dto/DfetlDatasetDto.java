package com.dfygt.dfetl.server.dto;

import lombok.Data;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/** 标准数据集、分表策略摘要和机构路由预检概况。 */
@Data
public class DfetlDatasetDto {
    private Long id;
    private String medicalDatasetId;
    private String datasetCode;
    private String datasetName;
    private String contractHash;
    private String datasetStatus;
    private Instant lastSyncedAt;
    private Integer fieldCount = 0;
    private Integer primaryKeyCount = 0;
    private DfetlSyncPolicyDto syncPolicy;
    private DfetlValidationPolicyDto validationPolicy;
    private DfetlMessagePolicyDto messagePolicy;
    private Integer routeCount = 0;
    private Integer passedRouteCount = 0;
    private Integer failedRouteCount = 0;
    private Integer pendingRouteCount = 0;
    private List<DfetlFieldDto> fields = new ArrayList<>();
    private Instant createdAt;
    private Instant updatedAt;
}
