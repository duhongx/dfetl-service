package com.dfygt.dfetl.server.service;

import com.dfygt.dfetl.server.dto.MessagePublishConfigDto;
import com.dfygt.dfetl.server.dto.SyncTaskDto;
import com.dfygt.dfetl.server.dto.TaskValidationConfigDto;
import com.dfygt.dfetl.server.dto.TaskViewConfigDto;
import com.dfygt.dfetl.server.dto.ValidationPolicy;
import com.dfygt.dfetl.server.entity.DfetlDataset;
import com.dfygt.dfetl.server.entity.DfetlField;
import com.dfygt.dfetl.server.entity.DfetlMessagePolicy;
import com.dfygt.dfetl.server.entity.DfetlSyncPolicy;
import com.dfygt.dfetl.server.entity.DfetlValidationPolicy;
import com.dfygt.dfetl.server.entity.InstitutionDatasetRoute;
import com.dfygt.dfetl.server.medical.MedicalContractSnapshotCodec;
import com.dfygt.dfetl.server.medical.MedicalDatasetContract;
import com.dfygt.dfetl.server.medical.MedicalFieldContract;
import com.dfygt.dfetl.server.scheduler.model.ScheduleConfig;
import com.dfygt.dfetl.server.scheduler.model.ScheduleMode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/** 将标准快照、机构路由和系统默认组装为一次性的普通任务创建快照。 */
@Service
@RequiredArgsConstructor
public class DatasetTaskSnapshotAssembler {

    private final SourceDataSourceService sourceDataSourceService;
    private final GlobalSettingsService globalSettingsService;
    private final DfetlPolicyService policyService;
    private final ObjectMapper objectMapper;

    public SyncTaskDto assemble(ResolvedDatasetRoute resolved, TaskCreateIntent intent) {
        DfetlDataset dataset = resolved.dataset();
        InstitutionDatasetRoute route = resolved.route();
        DfetlSyncPolicy syncPolicy = policyService.requireSync(dataset.getId());
        DfetlValidationPolicy validationPolicy = policyService.requireValidation(dataset.getId());
        DfetlMessagePolicy messagePolicy = policyService.requireMessage(dataset.getId());
        Map<String, SourceDataSourceService.ColumnInfo> sourceColumns = sourceColumns(resolved);
        List<DfetlField> fields = resolved.fields().stream()
                .filter(Objects::nonNull)
                .filter(field -> "ACTIVE".equalsIgnoreCase(field.getFieldStatus()))
                .sorted(Comparator
                        .comparingInt((DfetlField field) -> field.getFieldOrder() == null ? 0 : field.getFieldOrder())
                        .thenComparing(DfetlField::getFieldCode, String.CASE_INSENSITIVE_ORDER))
                .toList();

        List<Map<String, Object>> mappings = new ArrayList<>();
        List<String> upsertKeys = new ArrayList<>();
        Map<String, String> actualFieldNames = new LinkedHashMap<>();
        for (DfetlField field : fields) {
            String normalized = normalize(field.getFieldCode());
            SourceDataSourceService.ColumnInfo sourceColumn = sourceColumns.get(normalized);
            if (sourceColumn == null) {
                throw new IllegalStateException("ROUTE_STALE: 源对象缺少标准字段 " + field.getFieldCode());
            }
            String targetField = normalizeRequired(field.getTargetFieldCode(), "targetFieldCode").toLowerCase(Locale.ROOT);
            String targetType = normalizeRequired(field.getDorisType(), "dorisType").toUpperCase(Locale.ROOT);
            actualFieldNames.put(normalized, sourceColumn.columnName());
            if (Boolean.TRUE.equals(field.getPrimaryKey())) {
                upsertKeys.add(targetField);
            }
            Map<String, Object> mapping = new LinkedHashMap<>();
            mapping.put("sourceField", sourceColumn.columnName());
            mapping.put("fieldCode", field.getFieldCode());
            mapping.put("fieldName", field.getFieldName());
            mapping.put("targetField", targetField);
            mapping.put("included", true);
            mapping.put("sourceType", sourceColumn.dataType());
            mapping.put("targetType", targetType);
            mapping.put("primaryKey", Boolean.TRUE.equals(field.getPrimaryKey()));
            mapping.put("upsertKey", Boolean.TRUE.equals(field.getPrimaryKey()));
            mapping.put("requiredByStandard", Boolean.TRUE.equals(field.getRequiredByStandard()));
            mapping.put("valueDomainCode", field.getValueDomainCode());
            mapping.put("standardType", field.getStandardType());
            mapping.put("standardFormat", field.getStandardFormat());
            mapping.put("standardVersion", field.getStandardVersion());
            mappings.add(mapping);
        }

        String syncMode = upperDefault(syncPolicy.getWriteMode(), "TRUNCATE");
        if ("UPSERT".equals(syncMode) && upsertKeys.isEmpty()) {
            throw new IllegalStateException("UPSERT 任务缺少医共体标准主键: " + dataset.getDatasetCode());
        }
        String dataScope = defaultDataScope(syncPolicy.getSyncTemplate());
        String incrementalField = syncPolicy.getIncrementalField();
        if ("INCREMENTAL".equals(dataScope)) {
            String normalizedIncrement = normalize(normalizeRequired(incrementalField, "incrementalField"));
            incrementalField = actualFieldNames.get(normalizedIncrement);
            if (incrementalField == null) {
                throw new IllegalStateException("增量字段不在标准字段中: " + normalizedIncrement);
            }
        }

        SyncTaskDto dto = new SyncTaskDto();
        dto.setName(defaultText(intent.getName(), dataset.getDatasetCode()));
        dto.setInstitutionId(resolved.institution().getId());
        dto.setSourceMode("TABLE_VIEW");
        dto.setSourceDataSourceId(resolved.source().getId());
        dto.setTargetDataSourceId(resolved.target().getId());
        dto.setSourceSchema(route.getSourceSchema());
        dto.setViewNames(List.of(route.getSourceObject()));
        dto.setSourceObjectType(route.getSourceObjectType());
        dto.setTargetTableMap(writeJson(Map.of(route.getSourceObject(), route.getTargetTable())));
        dto.setSyncMode(syncMode);
        dto.setSyncType("FULL".equals(dataScope) ? "FULL" : "INCREMENTAL");
        dto.setDataScope(dataScope);
        dto.setInitialFullSync("INCREMENTAL".equals(dataScope)
                && "FULL_THEN_INCREMENT".equalsIgnoreCase(syncPolicy.getSyncTemplate()));
        dto.setUpsertKeys(upsertKeys);
        dto.setDorisTableModel("UPSERT".equals(syncMode) ? "UNIQUE_KEY" : "DUPLICATE_KEY");
        dto.setIncrementalField(incrementalField);
        dto.setIncrementMode(upperDefault(syncPolicy.getIncrementMode(), "TIME_FIELD"));
        dto.setUpperBoundStrategy(upperDefault(syncPolicy.getUpperBoundStrategy(), "CURRENT_TIME"));
        dto.setUpperBoundDelayMinutes(syncPolicy.getUpperBoundDelayMinutes());
        dto.setLookbackSeconds(syncPolicy.getLookbackSeconds());
        dto.setSequenceCol(incrementalField);
        dto.setBatchSize(syncPolicy.getFetchSize());
        int requestedReaderParallelism = syncPolicy.getReaderParallelism() == null
                ? 1 : Math.max(1, syncPolicy.getReaderParallelism());
        // 标准任务当前不生成 splitPk/ID_RANGE 分片条件；任务快照保存真实执行值，
        // 避免策略显示 4 而 SeaTunnel 最终静默降为 1。
        dto.setParallelism(1);
        dto.setRateLimit(syncPolicy.getRateLimit());
        dto.setExecutorType("SEATUNNEL_CLUSTER");
        dto.setWriterType("STREAM_LOAD");
        dto.setVersion("V1");
        dto.setVersionStatus("PUBLISHED");

        TaskViewConfigDto viewConfig = new TaskViewConfigDto();
        viewConfig.setViewName(route.getSourceObject());
        viewConfig.setFieldMappings(writeJson(mappings));
        dto.setViewConfigs(List.of(viewConfig));
        applySchedule(dto, syncPolicy);
        dto.setValidationConfig(validationSnapshot(validationPolicy));
        if (Boolean.TRUE.equals(messagePolicy.getEnabled())
                && DfetlPolicyService.isMessageDataset(dataset.getDatasetCode())) {
            dto.setMessagePublishConfig(messageSnapshot(messagePolicy, resolved));
        }
        dto.setDataCharacteristics(characteristics(
                resolved, fields, syncPolicy, validationPolicy, messagePolicy, requestedReaderParallelism));
        return dto;
    }

    private Map<String, SourceDataSourceService.ColumnInfo> sourceColumns(ResolvedDatasetRoute resolved) {
        List<SourceDataSourceService.ColumnInfo> columns = sourceDataSourceService.listColumns(
                resolved.source().getId(),
                resolved.route().getSourceSchema(),
                resolved.route().getSourceObject());
        Map<String, SourceDataSourceService.ColumnInfo> result = new LinkedHashMap<>();
        for (SourceDataSourceService.ColumnInfo column : columns) {
            String key = normalize(column.columnName());
            if (result.putIfAbsent(key, column) != null) {
                throw new IllegalStateException("源对象存在大小写不敏感重名字段: " + column.columnName());
            }
        }
        return result;
    }

    private void applySchedule(SyncTaskDto dto, DfetlSyncPolicy policy) {
        boolean enabled = Boolean.TRUE.equals(policy.getScheduleEnabled());
        String mode = upperDefault(policy.getScheduleMode(), "MANUAL");
        if (!enabled) {
            mode = "MANUAL";
        }
        ScheduleConfig config = new ScheduleConfig();
        config.setVersion(1);
        config.setTimezone(defaultText(policy.getScheduleTimezone(), "Asia/Shanghai"));
        if ("CRON".equals(mode) || "ADVANCED".equals(mode)) {
            config.setMode(ScheduleMode.ADVANCED);
            config.setCronExpression(normalizeRequired(policy.getScheduleCron(), "scheduleCron"));
        } else if ("EVERY_N_HOURS".equals(mode)) {
            config.setMode(ScheduleMode.EVERY_N_HOURS);
            config.setIntervalHours(policy.getScheduleIntervalHours());
            config.setMinute(0);
        } else {
            config.setMode(ScheduleMode.MANUAL);
        }
        dto.setScheduleConfig(writeJson(config));
        dto.setScheduleTimezone(config.getTimezone());
        dto.setStatus(config.getMode() == ScheduleMode.MANUAL ? "DISABLED" : "ENABLED");
    }

    private TaskValidationConfigDto validationSnapshot(DfetlValidationPolicy configured) {
        if (Boolean.TRUE.equals(configured.getInheritGlobal())) {
            return validationSnapshot(globalSettingsService.getValidationPolicy());
        }
        TaskValidationConfigDto dto = new TaskValidationConfigDto();
        dto.setEnabled(Boolean.TRUE.equals(configured.getEnabled()));
        dto.setMethod(upperDefault(configured.getValidationMethod(), "ROW_COUNT"));
        dto.setAutoTrigger(Boolean.TRUE.equals(configured.getEnabled())
                && "AFTER_SYNC".equalsIgnoreCase(configured.getTriggerMode()));
        dto.setBlockOnFail(Boolean.TRUE.equals(configured.getFailBlock()));
        dto.setTolerancePct(configured.getRowTolerance() == null ? BigDecimal.ZERO : configured.getRowTolerance());
        dto.setValidationLookbackHours(configured.getLookbackHours());
        return dto;
    }

    private static TaskValidationConfigDto validationSnapshot(ValidationPolicy policy) {
        ValidationPolicy resolved = policy == null ? ValidationPolicy.defaults() : policy;
        TaskValidationConfigDto dto = new TaskValidationConfigDto();
        dto.setEnabled(resolved.autoEnabled());
        dto.setMethod(resolved.method().toUpperCase(Locale.ROOT));
        dto.setAutoTrigger(resolved.autoEnabled() && "after_sync".equalsIgnoreCase(resolved.trigger()));
        dto.setBlockOnFail(resolved.failBlock());
        dto.setTolerancePct(BigDecimal.valueOf(resolved.rowTolerance()));
        dto.setValidationLookbackHours(resolved.lookbackHours());
        return dto;
    }

    private MessagePublishConfigDto messageSnapshot(DfetlMessagePolicy policy, ResolvedDatasetRoute resolved) {
        String routingKey = normalizeRequired(policy.getRoutingKey(), "messageRoutingKey")
                .toUpperCase(Locale.ROOT);
        MessagePublishConfigDto dto = new MessagePublishConfigDto();
        dto.setEnabled(true);
        dto.setMessageType(routingKey);
        dto.setTopic(defaultText(policy.getTopic(), routingKey));
        dto.setChannel(dto.getTopic());
        dto.setMessageKeyTemplate(policy.getKeyTemplate());
        dto.setFullSyncMode(messageFullSyncMode(policy.getFullSyncMode()));
        dto.setRateLimit(policy.getRateLimit());
        dto.setPageSize(policy.getPageSize());
        dto.setTenantId(defaultText(policy.getTenantId(), "0"));
        dto.setSourceSystem(defaultText(policy.getSourceSystem(),
                defaultText(resolved.source().getSourceCode(), "HIS")));
        List<Map<String, Object>> mapping = resolved.fields().stream()
                .filter(field -> "ACTIVE".equalsIgnoreCase(field.getFieldStatus()))
                .sorted(Comparator.comparingInt(field -> field.getFieldOrder() == null ? 0 : field.getFieldOrder()))
                .map(field -> {
                    String target = normalize(field.getFieldCode());
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("sourceField", target);
                    item.put("targetField", target);
                    item.put("standardField", field.getFieldCode());
                    item.put("primaryKey", Boolean.TRUE.equals(field.getPrimaryKey()));
                    return item;
                }).toList();
        dto.setFieldMappingJson(writeJson(mapping));
        return dto;
    }

    private static String messageFullSyncMode(String value) {
        String mode = upperDefault(value, "ALL");
        if ("NONE".equals(mode)) {
            return "SKIP";
        }
        return switch (mode) {
            case "ALL", "SKIP", "NOTIFY_ONLY" -> mode;
            default -> throw new IllegalStateException("不支持的首次全量消息模式: " + mode);
        };
    }

    private String characteristics(ResolvedDatasetRoute resolved, List<DfetlField> fields,
                                   DfetlSyncPolicy sync,
                                   DfetlValidationPolicy validation, DfetlMessagePolicy message,
                                   int requestedReaderParallelism) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("fillSource", "STANDARD_DATASET_ROUTE");
        values.put("standardDatasetId", resolved.dataset().getId());
        values.put("medicalDatasetId", resolved.dataset().getMedicalDatasetId());
        values.put("institutionDatasetRouteId", resolved.route().getId());
        values.put("standardContractHash", resolved.dataset().getContractHash());
        values.put("routeRevision", resolved.route().getRouteRevision());
        values.put("syncPolicyRevision", sync.getPolicyRevision());
        values.put("validationPolicyRevision", validation.getPolicyRevision());
        values.put("messagePolicyRevision", message.getPolicyRevision());
        values.put("requestedReaderParallelism", requestedReaderParallelism);
        values.put("effectiveReaderParallelism", 1);
        if (requestedReaderParallelism > 1) {
            values.put("readerParallelismDowngradeReason", "NO_VALID_PARTITION_COLUMN");
        }
        values.put("matchedDatasetCode", resolved.dataset().getDatasetCode());
        values.put("medicalMappingMode", "CONTRACT_DRIVEN");
        MedicalContractSnapshotCodec.pin(values, medicalContract(resolved, fields), objectMapper);
        return writeJson(values);
    }

    private static MedicalDatasetContract medicalContract(
            ResolvedDatasetRoute resolved, List<DfetlField> fields) {
        List<MedicalFieldContract> contractFields = fields.stream()
                .map(field -> new MedicalFieldContract(
                        field.getFieldCode(),
                        field.getFieldName(),
                        field.getStandardType(),
                        field.getStandardFormat(),
                        field.getFieldOrder(),
                        Boolean.TRUE.equals(field.getPrimaryKey()),
                        Boolean.TRUE.equals(field.getRequiredByStandard()),
                        field.getTargetFieldCode(),
                        field.getDorisType(),
                        field.getValueDomainCode()))
                .toList();
        List<String> primaryKeys = fields.stream()
                .filter(field -> Boolean.TRUE.equals(field.getPrimaryKey()))
                .map(DfetlField::getFieldCode)
                .toList();
        String version = fields.stream()
                .map(DfetlField::getStandardVersion)
                .filter(value -> value != null && !value.isBlank())
                .findFirst()
                .orElse(null);
        return new MedicalDatasetContract(
                resolved.dataset().getDatasetCode(),
                resolved.dataset().getDatasetName(),
                version,
                resolved.route().getTargetTable(),
                contractFields,
                primaryKeys,
                standardFieldCode(fields, "XIUGAISJ"),
                standardFieldCode(fields, "ZUOFEIBZ"));
    }

    private static String standardFieldCode(List<DfetlField> fields, String code) {
        return fields.stream()
                .map(DfetlField::getFieldCode)
                .filter(fieldCode -> code.equalsIgnoreCase(fieldCode))
                .findFirst()
                .orElse(null);
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception exception) {
            throw new IllegalStateException("任务快照 JSON 序列化失败", exception);
        }
    }

    private static String defaultDataScope(String template) {
        String normalized = upperDefault(template, "FULL_THEN_INCREMENT");
        return "FULL".equals(normalized) || "FULL_ONLY".equals(normalized) ? "FULL" : "INCREMENTAL";
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private static String normalizeRequired(String value, String field) {
        String normalized = value == null || value.isBlank() ? null : value.trim();
        if (normalized == null) {
            throw new IllegalStateException(field + " 不能为空");
        }
        return normalized;
    }

    private static String upperDefault(String value, String fallback) {
        return defaultText(value, fallback).toUpperCase(Locale.ROOT);
    }

    private static String defaultText(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }
}
