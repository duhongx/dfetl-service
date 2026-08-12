package com.dfygt.dfetl.server.medical.quality;

import com.dfygt.dfetl.server.common.TargetTableMapParser;
import com.dfygt.dfetl.server.entity.SourceDataSource;
import com.dfygt.dfetl.server.entity.SyncTask;
import com.dfygt.dfetl.server.entity.TaskExecution;
import com.dfygt.dfetl.server.medical.MedicalDatasetContract;
import com.dfygt.dfetl.server.medical.MedicalDatasetContractService;
import com.dfygt.dfetl.server.medical.MedicalContractSnapshotCodec;
import com.dfygt.dfetl.server.medical.owner.MedicalDatasetOwnerResolver;
import com.dfygt.dfetl.server.medical.precheck.MedicalDatasetContractOverrideApplier;
import com.dfygt.dfetl.server.medical.precheck.MedicalDatasetFieldOverride;
import com.dfygt.dfetl.server.medical.precheck.MedicalValueDomainService;
import com.dfygt.dfetl.server.medical.source.SourceDialectAdapter;
import com.dfygt.dfetl.server.medical.source.SourceDialectAdapterResolver;
import com.dfygt.dfetl.server.medical.source.MedicalSourceSelectBuilder;
import com.dfygt.dfetl.server.medical.source.MedicalSourceSelectPlan;
import com.dfygt.dfetl.server.repository.SourceDataSourceRepository;
import com.dfygt.dfetl.server.service.SourceDataSourceService;
import com.dfygt.dfetl.server.service.TaskViewSnapshotReader;
import com.dfygt.dfetl.server.service.WatermarkService;
import com.dfygt.dfetl.server.service.WhereClauseBuilder;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Locale;

/**
 * 医共体执行前问题行分流入口。
 */
@Service
@RequiredArgsConstructor
public class MedicalDirtyExecutionService {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final SourceDataSourceRepository sourceRepository;
    private final SourceDataSourceService sourceDataSourceService;
    private final MedicalDatasetContractService contractService;
    private final SourceDialectAdapterResolver adapterResolver;
    private final MedicalRowQualityPlanBuilder planBuilder;
    private final MedicalValueDomainService valueDomainService;
    private final MedicalDirtyRecordService dirtyRecordService;
    private final MedicalDatasetOwnerResolver ownerResolver;
    private final WhereClauseBuilder whereClauseBuilder;
    private final TaskViewSnapshotReader taskViewSnapshotReader;
    private final MedicalDatasetContractOverrideApplier contractOverrideApplier;
    private final TargetWriteContractService targetWriteContractService;
    private final MedicalSourceSelectBuilder sourceSelectBuilder;

    /**
     * 标准数据集路由的正式 Writer 写安全计划。这里只过滤 Doris 物理必然拒绝的行，
     * 不复用旧质量分流中的必填、占位值、重复键和值域规则。
     */
    public MedicalDirtyExecutionResult prepareWriteSafety(
            SyncTask task,
            WatermarkService.WindowContext window,
            TaskExecution execution) {
        MedicalRuntimeOptions options = medicalRuntimeOptions(task);
        if (options.datasetCode() == null) {
            throw new IllegalStateException("标准数据集任务缺少 matchedDatasetCode");
        }
        if (task.getViewNames() == null || task.getViewNames().size() != 1) {
            throw new IllegalStateException("标准数据集写安全计划只支持单源对象任务");
        }
        String sourceView = task.getViewNames().get(0);
        SourceDataSource source = sourceRepository.findById(task.getSourceDataSourceId())
                .orElseThrow(() -> new NoSuchElementException("SourceDataSource not found: "
                        + task.getSourceDataSourceId()));
        String sourceSchema = firstNonBlank(task.getSourceSchema(), source.getSchemaName());
        List<MedicalDatasetFieldOverride> fieldOverrides = fieldOverridesForTask(task);
        Map<String, String> effectiveFieldMapping = effectiveFieldMapping(
                options.fieldMapping(), fieldOverrides);
        MedicalDatasetContract contract = MedicalContractSnapshotCodec.resolveForTask(
                task, contractService, OBJECT_MAPPER);
        SourceDialectAdapter adapter = adapterResolver.resolve(
                source.getType(), options.compatibilityMode());
        List<SourceDataSourceService.ColumnInfo> columns = sourceDataSourceService.listColumns(
                task.getSourceDataSourceId(), sourceSchema, sourceView);
        String targetTable = resolveTargetTable(task, sourceView);
        TargetWriteContract targetContract = targetWriteContractService.resolve(
                task.getTargetDataSourceId(), targetTable, contract);
        String baseWhere = whereClauseBuilder.build(task, source.getType(), window);
        MedicalSourceSelectPlan plan = sourceSelectBuilder.buildWriteSafeSelect(
                sourceSchema,
                sourceView,
                contract,
                columns,
                adapter,
                effectiveFieldMapping,
                targetContract,
                baseWhere);
        if (plan.hasBlockers()) {
            throw new IllegalStateException("标准数据集写安全计划生成失败: "
                    + String.join("；", plan.blockers()));
        }
        return new MedicalDirtyExecutionResult(true, 0, 0, plan.sql());
    }

    public MedicalDirtyExecutionResult prepare(
            SyncTask task,
            WatermarkService.WindowContext window,
            TaskExecution execution) {
        MedicalRuntimeOptions options = medicalRuntimeOptions(task);
        if (options.datasetCode() == null) {
            return MedicalDirtyExecutionResult.empty();
        }
        if (task.getViewNames() == null || task.getViewNames().size() != 1) {
            return MedicalDirtyExecutionResult.empty();
        }
        if (task.getSyncMode() != null && "APPEND".equalsIgnoreCase(task.getSyncMode().trim())) {
            throw new IllegalStateException("医共体问题行分流暂不支持 APPEND 写入模式，避免重跑重复插入");
        }

        String sourceView = task.getViewNames().get(0);
        SourceDataSource source = sourceRepository.findById(task.getSourceDataSourceId())
                .orElseThrow(() -> new NoSuchElementException("SourceDataSource not found: "
                        + task.getSourceDataSourceId()));
        String sourceSchema = firstNonBlank(task.getSourceSchema(), source.getSchemaName());
        List<MedicalDatasetFieldOverride> fieldOverrides = fieldOverridesForTask(task);
        Map<String, String> effectiveFieldMapping = effectiveFieldMapping(options.fieldMapping(), fieldOverrides);
        MedicalDatasetContract contract = contractOverrideApplier.apply(
                contractService.loadByDatasetCode(options.datasetCode()),
                fieldOverrides);
        SourceDialectAdapter adapter = adapterResolver.resolve(source.getType(), options.compatibilityMode());
        List<SourceDataSourceService.ColumnInfo> columns = sourceDataSourceService.listColumns(
                task.getSourceDataSourceId(), sourceSchema, sourceView);
        String baseWhere = whereClauseBuilder.build(task, source.getType(), window);
        String ownerName = ownerResolver.resolveOwnerName(options.datasetCode());
        String targetTable = resolveTargetTable(task, sourceView);
        long excludedRows = 0;
        long warningRows = 0;
        String validSourceQuery;
        try (Connection connection = sourceDataSourceService.openConnection(task.getSourceDataSourceId())) {
            Map<String, com.dfygt.dfetl.server.medical.precheck.MedicalValueDomainRule> valueDomainRules =
                    fieldOverrides.isEmpty()
                            ? valueDomainService.rulesByField(options.datasetCode())
                            : valueDomainService.rulesByField(options.datasetCode(), fieldOverrides);
            valueDomainRules = valueDomainService.resolveActualInvalidBlocks(
                    connection,
                    adapter,
                    sourceSchema,
                    sourceView,
                    contract,
                    columns,
                    effectiveFieldMapping,
                    baseWhere,
                    valueDomainRules);
            MedicalRowQualityPlan plan = planBuilder.build(new MedicalRowQualityRequest(
                    sourceSchema,
                    sourceView,
                    contract,
                    columns,
                    adapter,
                    effectiveFieldMapping,
                    baseWhere,
                    valueDomainRules));
            validSourceQuery = plan.validSourceQuery();
            if (!plan.hasRowBlockingChecks() && !plan.hasWarningChecks()) {
                return new MedicalDirtyExecutionResult(true, 0, 0, validSourceQuery);
            }
            if (plan.hasRowBlockingChecks()) {
                excludedRows = persistRows(
                        connection,
                        plan.blockingRowsQuery(),
                        task,
                        execution,
                        contract,
                        sourceSchema,
                        sourceView,
                        targetTable,
                        ownerName,
                        "EXCLUDED",
                        window);
            }
            if (plan.hasWarningChecks()) {
                warningRows = persistRows(
                        connection,
                        plan.warningRowsQuery(),
                        task,
                        execution,
                        contract,
                        sourceSchema,
                        sourceView,
                        targetTable,
                        ownerName,
                        "WRITTEN_WITH_WARNING",
                        window);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("医共体问题行分流查询失败: " + e.getMessage(), e);
        }
        return new MedicalDirtyExecutionResult(true, excludedRows, warningRows, validSourceQuery);
    }

    private List<MedicalDatasetFieldOverride> fieldOverridesForTask(SyncTask task) {
        if (task == null || task.getId() == null) {
            return List.of();
        }
        return taskViewSnapshotReader.fieldOverrides(task.getId());
    }

    private static Map<String, String> effectiveFieldMapping(
            Map<String, String> taskFieldMapping,
            List<MedicalDatasetFieldOverride> fieldOverrides) {
        Map<String, String> result = new LinkedHashMap<>();
        if (taskFieldMapping != null) {
            result.putAll(taskFieldMapping);
        }
        if (fieldOverrides == null || fieldOverrides.isEmpty()) {
            return result;
        }
        for (MedicalDatasetFieldOverride override : fieldOverrides) {
            putMapping(result, override.fieldCode(), override.sourceColumn());
            putMapping(result, override.targetColumn(), override.sourceColumn());
        }
        return result;
    }

    private static void putMapping(Map<String, String> mapping, String key, String value) {
        String normalizedKey = blankToNull(key);
        String normalizedValue = blankToNull(value);
        if (normalizedKey == null || normalizedValue == null) {
            return;
        }
        mapping.put(normalizedKey, normalizedValue);
        mapping.put(normalizedKey.toUpperCase(Locale.ROOT), normalizedValue);
        mapping.put(normalizedKey.toLowerCase(Locale.ROOT), normalizedValue);
    }

    private long persistRows(
            Connection connection,
            String sql,
            SyncTask task,
            TaskExecution execution,
            MedicalDatasetContract contract,
            String sourceSchema,
            String sourceView,
            String targetTable,
            String ownerName,
            String rowAction,
            WatermarkService.WindowContext window) throws SQLException {
        if (sql == null || sql.isBlank()) {
            return 0;
        }
        long count = 0;
        try (var ps = connection.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                String errorType = rs.getString("error_type");
                String severity = rs.getString("severity");
                MedicalDirtyFieldIssue field = new MedicalDirtyFieldIssue(
                        optionalString(rs, "field_code", errorType),
                        optionalString(rs, "field_name", errorType),
                        optionalString(rs, "source_column", null),
                        optionalString(rs, "target_column", null),
                        errorType,
                        optionalString(rs, "standard_rule", null),
                        optionalString(rs, "raw_value", null),
                        optionalString(rs, "normalized_value", null),
                        errorType,
                        severity,
                        optionalString(rs, "value_domain_code", null),
                        optionalString(rs, "value_domain_mode", null),
                        optionalInteger(rs, "value_domain_allowed_count"));
                dirtyRecordService.upsertDirtyRow(new MedicalDirtyRowIssue(
                        task.getId(),
                        execution.getId(),
                        contract.datasetCode(),
                        contract.datasetName(),
                        sourceSchema,
                        sourceView,
                        targetTable,
                        rs.getString("business_pk_json"),
                        rs.getString("source_row_hash"),
                        windowJson(window),
                        ownerName,
                        "DATASET_OWNER_RESOLVER",
                        rowAction,
                        severity,
                        rs.getString("raw_row_json"),
                        List.of(field)));
                count++;
            }
        }
        return count;
    }

    private static String optionalString(ResultSet rs, String column, String fallback) {
        try {
            String value = rs.getString(column);
            return value == null ? fallback : value;
        } catch (SQLException ignored) {
            return fallback;
        }
    }

    private static Integer optionalInteger(ResultSet rs, String column) {
        try {
            Object value = rs.getObject(column);
            if (value == null) {
                return null;
            }
            if (value instanceof Number number) {
                return number.intValue();
            }
            return Integer.valueOf(String.valueOf(value));
        } catch (Exception ignored) {
            return null;
        }
    }

    private static String resolveTargetTable(SyncTask task, String sourceView) {
        Map<String, String> targetMap = TargetTableMapParser.parseStrict(
                task.getTargetTableMap(), OBJECT_MAPPER, null);
        if (targetMap != null && targetMap.containsKey(sourceView)) {
            return targetMap.get(sourceView);
        }
        return sourceView;
    }

    private static String windowJson(WatermarkService.WindowContext window) {
        try {
            Map<String, Object> values = new LinkedHashMap<>();
            values.put("windowType", window == null ? null : window.windowType());
            values.put("windowStart", window == null || window.windowStart() == null ? null : window.windowStart().toString());
            values.put("windowEnd", window == null || window.windowEnd() == null ? null : window.windowEnd().toString());
            values.put("windowStartId", window == null ? null : window.windowStartId());
            values.put("windowEndId", window == null ? null : window.windowEndId());
            return OBJECT_MAPPER.writeValueAsString(values);
        } catch (Exception e) {
            return "{}";
        }
    }

    private static MedicalRuntimeOptions medicalRuntimeOptions(SyncTask task) {
        String raw = task == null ? null : task.getDataCharacteristics();
        if (raw == null || raw.isBlank()) {
            return MedicalRuntimeOptions.empty();
        }
        try {
            Map<String, Object> values = OBJECT_MAPPER.readValue(raw, new TypeReference<Map<String, Object>>() {});
            Object mode = values.get("medicalMappingMode");
            if (mode == null || !"CONTRACT_DRIVEN".equalsIgnoreCase(mode.toString())) {
                return MedicalRuntimeOptions.empty();
            }
            Object datasetCode = values.get("matchedDatasetCode");
            if (datasetCode == null || datasetCode.toString().isBlank()) {
                return MedicalRuntimeOptions.empty();
            }
            Object compatibilityMode = values.get("compatibilityMode");
            return new MedicalRuntimeOptions(
                    datasetCode.toString().trim().toUpperCase(Locale.ROOT),
                    compatibilityMode == null ? null : compatibilityMode.toString().trim(),
                    parseStringMap(values.get("fieldMapping")));
        } catch (Exception e) {
            throw new IllegalStateException("医共体任务 dataCharacteristics 不是合法 JSON: " + e.getMessage(), e);
        }
    }

    private static Map<String, String> parseStringMap(Object value) {
        if (!(value instanceof Map<?, ?> raw) || raw.isEmpty()) {
            return Map.of();
        }
        Map<String, String> parsed = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : raw.entrySet()) {
            if (entry.getKey() != null && entry.getValue() != null) {
                parsed.put(entry.getKey().toString(), entry.getValue().toString());
            }
        }
        return parsed;
    }

    private static String firstNonBlank(String first, String second) {
        if (first != null && !first.isBlank()) {
            return first.trim();
        }
        return second == null || second.isBlank() ? null : second.trim();
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private record MedicalRuntimeOptions(
            String datasetCode,
            String compatibilityMode,
            Map<String, String> fieldMapping) {

        static MedicalRuntimeOptions empty() {
            return new MedicalRuntimeOptions(null, null, Map.of());
        }
    }
}
