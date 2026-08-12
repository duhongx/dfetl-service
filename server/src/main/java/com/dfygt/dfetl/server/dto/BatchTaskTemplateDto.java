package com.dfygt.dfetl.server.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

@Data
public class BatchTaskTemplateDto {

    private Long id;

    @NotBlank
    private String name;

    private String description;

    // ── 目标配置 ────────────────────────────────────────────────────────────────

    @NotNull
    private Long targetDatasourceId;

    @NotBlank
    private String targetTable;

    // ── 源端配置（模板级，各机构共用） ──────────────────────────────────────────

    @NotBlank
    private String viewName;

    private String sourceSchema;

    // ── 同步配置 ────────────────────────────────────────────────────────────────

    private String dataScope = "INCREMENTAL";

    private String incrementMode = "TIME_FIELD";

    private String incrementalField;

    private String syncMode = "UPSERT";

    /** JSON 数组：UPSERT 主键列 */
    private String upsertKeys;

    private Integer parallelism = 1;

    private String cronExpression;

    // ── 校验配置 ────────────────────────────────────────────────────────────────

    private String validationMethod = "CHECKSUM";

    private String validationDriftCron;

    private Integer validationLookbackHours = 24;

    private Boolean autoTrigger = true;

    // ── Doris 配置 ──────────────────────────────────────────────────────────────

    private String dorisTableModel = "UNIQUE_KEY";

    private Boolean enableDorisMerge = false;

    private String softDeleteField;

    private String deleteSignValue = "1";

    private String sequenceCol;

    // ── 关联数据源列表 ──────────────────────────────────────────────────────────

    private List<BatchTaskTemplateSourceDto> sources;

    // ── 元数据 ──────────────────────────────────────────────────────────────────

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
