package com.dfygt.dfetl.server.medical.source;

import com.dfygt.dfetl.server.medical.MedicalDatasetContract;
import com.dfygt.dfetl.server.medical.MedicalFieldContract;
import com.dfygt.dfetl.server.medical.MedicalStorageNumericPolicy;
import com.dfygt.dfetl.server.medical.MedicalTemporalRule;
import com.dfygt.dfetl.server.medical.quality.TargetWriteContract;
import com.dfygt.dfetl.server.service.SourceDataSourceService.ColumnInfo;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 基于医共体字段契约生成源端标准化 SELECT。
 */
@Component
public class MedicalSourceSelectBuilder {

    private static final Set<String> BOUNDED_TEXT_TYPES = Set.of("VARCHAR", "CHAR");

    public MedicalSourceSelectPlan buildSelect(
            String sourceSchema,
            String sourceObject,
            MedicalDatasetContract contract,
            List<ColumnInfo> sourceColumns,
            SourceDialectAdapter dialectAdapter,
            Map<String, String> fieldMapping) {
        if (contract == null) {
            throw new IllegalArgumentException("医共体数据集契约不能为空");
        }
        if (dialectAdapter == null) {
            throw new IllegalArgumentException("源库方言适配器不能为空");
        }
        if (sourceObject == null || sourceObject.isBlank()) {
            throw new IllegalArgumentException("源对象不能为空");
        }

        Map<String, String> sourceColumnIndex = sourceColumnIndex(sourceColumns);
        Map<String, String> normalizedMapping = normalizeMapping(fieldMapping);
        List<MedicalFieldContract> fields = orderedFields(contract);

        List<String> blockers = new ArrayList<>();
        List<String> selectedColumns = new ArrayList<>();
        Set<String> usedSourceColumns = new LinkedHashSet<>();
        List<String> projections = new ArrayList<>();

        for (MedicalFieldContract field : fields) {
            String sourceColumn = resolveSourceColumn(field, sourceColumnIndex, normalizedMapping);
            if (sourceColumn == null) {
                blockers.add("标准字段缺失: " + field.code());
                continue;
            }
            usedSourceColumns.add(normalize(sourceColumn));
            selectedColumns.add(field.dorisColumn());
            String sourceExpression = dialectAdapter.quoteIdentifier(sourceColumn);
            projections.add(fieldExpression(field, sourceExpression, dialectAdapter)
                    + " AS " + dialectAdapter.quoteIdentifier(field.dorisColumn()));
        }

        List<String> ignoredSourceFields = ignoredSourceFields(sourceColumns, usedSourceColumns);
        List<String> warnings = warnings(ignoredSourceFields);
        if (!blockers.isEmpty()) {
            return new MedicalSourceSelectPlan(
                    "",
                    List.of(),
                    List.copyOf(blockers),
                    warnings,
                    ignoredSourceFields);
        }

        String sql = "SELECT " + String.join(", ", projections)
                + " FROM " + qualifySource(sourceSchema, sourceObject, dialectAdapter);
        return new MedicalSourceSelectPlan(
                sql,
                List.copyOf(selectedColumns),
                List.of(),
                warnings,
                ignoredSourceFields);
    }

    /**
     * 生成单个标准字段的源端读取表达式，用于 WHERE / 水位 / 校验等链路复用
     * SELECT 投影中的同一套字段转换规则。
     */
    public String buildFieldExpression(
            MedicalDatasetContract contract,
            String fieldCodeOrDorisColumn,
            List<ColumnInfo> sourceColumns,
            SourceDialectAdapter dialectAdapter,
            Map<String, String> fieldMapping) {
        if (contract == null) {
            throw new IllegalArgumentException("医共体数据集契约不能为空");
        }
        if (fieldCodeOrDorisColumn == null || fieldCodeOrDorisColumn.isBlank()) {
            throw new IllegalArgumentException("医共体字段不能为空");
        }
        if (dialectAdapter == null) {
            throw new IllegalArgumentException("源库方言适配器不能为空");
        }
        MedicalFieldContract field = orderedFields(contract).stream()
                .filter(candidate -> sameField(candidate, fieldCodeOrDorisColumn))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "医共体标准字段不存在: " + fieldCodeOrDorisColumn));
        String sourceColumn = resolveSourceColumn(
                field,
                sourceColumnIndex(sourceColumns),
                normalizeMapping(fieldMapping));
        if (sourceColumn == null) {
            throw new IllegalArgumentException("标准字段缺失: " + field.code());
        }
        return fieldExpression(field, dialectAdapter.quoteIdentifier(sourceColumn), dialectAdapter);
    }

    /**
     * 生成正式 Writer 使用的物理可写行集。字段投影与安全谓词复用同一转换表达式，
     * 只排除 Doris 严格写入必然拒绝的记录，不承载业务质量规则。
     */
    public MedicalSourceSelectPlan buildWriteSafeSelect(
            String sourceSchema,
            String sourceObject,
            MedicalDatasetContract contract,
            List<ColumnInfo> sourceColumns,
            SourceDialectAdapter dialectAdapter,
            Map<String, String> fieldMapping,
            TargetWriteContract targetContract,
            String baseWhere) {
        if (targetContract == null) {
            throw new IllegalArgumentException("Doris 目标写入合同不能为空");
        }
        MedicalSourceSelectPlan projection = buildSelect(
                sourceSchema,
                sourceObject,
                contract,
                sourceColumns,
                dialectAdapter,
                fieldMapping);
        if (projection.hasBlockers()) {
            return projection;
        }

        Map<String, String> sourceColumnIndex = sourceColumnIndex(sourceColumns);
        Map<String, String> normalizedMapping = normalizeMapping(fieldMapping);
        List<String> predicates = new ArrayList<>();
        if (baseWhere != null && !baseWhere.isBlank()) {
            predicates.add("(" + baseWhere.trim() + ")");
        }
        for (MedicalFieldContract field : orderedFields(contract)) {
            String sourceColumn = resolveSourceColumn(field, sourceColumnIndex, normalizedMapping);
            if (sourceColumn == null) {
                throw new IllegalStateException("标准字段缺失: " + field.code());
            }
            TargetWriteContract.PhysicalColumn physical = targetContract.column(field.dorisColumn());
            if (physical == null) {
                throw new IllegalStateException("Doris 目标写入合同缺少字段: " + field.dorisColumn());
            }
            String converted = fieldExpression(
                    field,
                    dialectAdapter.quoteIdentifier(sourceColumn),
                    dialectAdapter);
            if (Boolean.FALSE.equals(physical.nullable())) {
                predicates.add("(" + converted + " IS NOT NULL)");
            }
            if (BOUNDED_TEXT_TYPES.contains(baseType(physical.dataType()))
                    && physical.characterCapacity() != null) {
                predicates.add("(" + converted + " IS NULL OR "
                        + dialectAdapter.byteLength(converted) + " <= "
                        + physical.characterCapacity() + ")");
            }
        }

        String sql = projection.sql();
        if (!predicates.isEmpty()) {
            sql += " WHERE " + String.join(" AND ", predicates);
        }
        return new MedicalSourceSelectPlan(
                sql,
                projection.selectedColumns(),
                projection.blockers(),
                projection.warnings(),
                projection.ignoredSourceFields());
    }

    private static List<MedicalFieldContract> orderedFields(MedicalDatasetContract contract) {
        if (contract.fields() == null) {
            return List.of();
        }
        return contract.fields().stream()
                .sorted(Comparator.comparingInt(field -> field.order() == null ? 0 : field.order()))
                .toList();
    }

    private static Map<String, String> sourceColumnIndex(List<ColumnInfo> sourceColumns) {
        if (sourceColumns == null || sourceColumns.isEmpty()) {
            return Map.of();
        }
        Map<String, String> index = new LinkedHashMap<>();
        for (ColumnInfo column : sourceColumns) {
            if (column == null || column.columnName() == null || column.columnName().isBlank()) {
                continue;
            }
            index.putIfAbsent(normalize(column.columnName()), column.columnName());
        }
        return index;
    }

    private static Map<String, String> normalizeMapping(Map<String, String> fieldMapping) {
        if (fieldMapping == null || fieldMapping.isEmpty()) {
            return Map.of();
        }
        Map<String, String> normalized = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : fieldMapping.entrySet()) {
            if (entry.getKey() == null || entry.getKey().isBlank()
                    || entry.getValue() == null || entry.getValue().isBlank()) {
                continue;
            }
            normalized.put(normalize(entry.getKey()), entry.getValue().trim());
        }
        return normalized;
    }

    private static String resolveSourceColumn(
            MedicalFieldContract field,
            Map<String, String> sourceColumnIndex,
            Map<String, String> fieldMapping) {
        String mapped = firstNonBlank(
                fieldMapping.get(normalize(field.code())),
                fieldMapping.get(normalize(field.dorisColumn())));
        if (mapped != null) {
            return sourceColumnIndex.get(normalize(mapped));
        }
        return sourceColumnIndex.get(normalize(field.code()));
    }

    private static boolean sameField(MedicalFieldContract field, String fieldCodeOrDorisColumn) {
        String normalized = normalize(fieldCodeOrDorisColumn);
        return normalized.equals(normalize(field.code()))
                || normalized.equals(normalize(field.dorisColumn()));
    }

    private static String fieldExpression(
            MedicalFieldContract field,
            String sourceExpression,
            SourceDialectAdapter dialectAdapter) {
        String type = field.sdvType() == null ? "" : field.sdvType().trim().toUpperCase(Locale.ROOT);
        return switch (type) {
            case "DT", "D" -> dialectAdapter.safeTemporal(
                    sourceExpression,
                    MedicalTemporalRule.require(type, field.format()));
            case "N" -> dialectAdapter.safeDecimal(
                    sourceExpression,
                    MedicalStorageNumericPolicy.require(type, field.format()));
            default -> dialectAdapter.castToText(sourceExpression);
        };
    }

    private static String qualifySource(
            String sourceSchema,
            String sourceObject,
            SourceDialectAdapter dialectAdapter) {
        if (sourceSchema == null || sourceSchema.isBlank()) {
            return dialectAdapter.quoteIdentifier(sourceObject);
        }
        return dialectAdapter.quoteIdentifier(sourceSchema) + "." + dialectAdapter.quoteIdentifier(sourceObject);
    }

    private static List<String> ignoredSourceFields(List<ColumnInfo> sourceColumns, Set<String> usedSourceColumns) {
        if (sourceColumns == null || sourceColumns.isEmpty()) {
            return List.of();
        }
        return sourceColumns.stream()
                .filter(column -> column != null && column.columnName() != null && !column.columnName().isBlank())
                .filter(column -> !usedSourceColumns.contains(normalize(column.columnName())))
                .map(ColumnInfo::columnName)
                .collect(Collectors.toList());
    }

    private static List<String> warnings(List<String> ignoredSourceFields) {
        if (ignoredSourceFields == null || ignoredSourceFields.isEmpty()) {
            return List.of();
        }
        return ignoredSourceFields.stream()
                .map(field -> "标准外字段忽略: " + field)
                .collect(Collectors.toList());
    }

    private static String firstNonBlank(String first, String second) {
        if (first != null && !first.isBlank()) {
            return first;
        }
        if (second != null && !second.isBlank()) {
            return second;
        }
        return null;
    }

    private static String baseType(String value) {
        if (value == null) {
            return "";
        }
        int parenthesis = value.indexOf('(');
        return (parenthesis < 0 ? value : value.substring(0, parenthesis))
                .trim()
                .toUpperCase(Locale.ROOT);
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
    }
}
