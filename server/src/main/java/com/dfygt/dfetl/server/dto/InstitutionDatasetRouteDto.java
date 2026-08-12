package com.dfygt.dfetl.server.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.time.Instant;

@Data
public class InstitutionDatasetRouteDto {

    private Long id;

    @NotNull
    private Long institutionId;
    private String institutionCode;
    private String institutionName;

    @NotNull
    private Long datasetId;
    private String datasetCode;
    private String datasetName;

    @NotNull
    private Long sourceDatasourceId;
    private String sourceDatasourceName;

    @NotBlank
    private String sourceSchema;

    @NotBlank
    private String sourceObject;
    private String sourceObjectType = "VIEW";

    @NotNull
    private Long targetDatasourceId;
    private String targetDatasourceName;
    private String targetTable;

    private Boolean enabled = false;
    private String validationStatus = "PENDING";
    private String validationSummary;
    private String validationDetailsJson;
    private Instant lastValidatedAt;
    private String validatedContractHash;
    private Long validatedRouteRevision;
    private Long routeRevision;
    private Instant createdAt;
    private Instant updatedAt;
}
