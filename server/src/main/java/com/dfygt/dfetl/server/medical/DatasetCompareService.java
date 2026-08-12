package com.dfygt.dfetl.server.medical;

import com.dfygt.dfetl.server.service.SourceDataSourceService;
import com.dfygt.dfetl.server.service.SourceDataSourceService.ColumnInfo;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.sql.Types;
import java.util.*;

/**
 * 数据集字段对比服务：对比规范定义与源端视图的字段差异。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class DatasetCompareService {

    private final MedicalRegistryReader registryReader;
    private final SourceDataSourceService sourceDataSourceService;

    /**
     * 对比指定数据集的规范字段与源端视图字段。
     *
     * @param registryDsId 规范注册表数据源 ID（Doris df_ygt）
     * @param sourceDsId   源端数据源 ID（PostgreSQL）
     * @param datasetCode  数据集代码（如 YL_HUANZHEJBXX）
     * @return 对比结果
     */
    public DatasetCompareResult compare(Long registryDsId, Long sourceDsId, String datasetCode) {
        // 1. 从规范读取数据集字段定义
        List<DatasetDefinition> datasets = registryReader.loadDatasets(registryDsId);
        DatasetDefinition dataset = datasets.stream()
                .filter(d -> datasetCode.equalsIgnoreCase(d.shujujdm()))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("数据集不存在: " + datasetCode));

        // 2. 从源端读取视图字段
        String viewName = "v_" + datasetCode.toLowerCase();
        // 获取源端 schema
        var sourceDs = sourceDataSourceService.findById(sourceDsId);
        String schema = sourceDs.getSchema();

        List<ColumnInfo> sourceColumns;
        try {
            sourceColumns = sourceDataSourceService.listColumns(sourceDsId, schema, viewName);
        } catch (Exception e) {
            log.warn("[DatasetCompare] 获取源端视图字段失败: {}.{} - {}", schema, viewName, e.getMessage());
            sourceColumns = List.of();
        }

        // 3. 构建对比
        Map<String, FieldDefinition> registryMap = new LinkedHashMap<>();
        for (FieldDefinition field : dataset.fields()) {
            registryMap.put(field.ziduandm().toLowerCase(), field);
        }

        Map<String, ColumnInfo> sourceMap = new LinkedHashMap<>();
        for (ColumnInfo col : sourceColumns) {
            sourceMap.put(col.columnName().toLowerCase(), col);
        }

        // 4. 合并所有字段名
        Set<String> allFields = new LinkedHashSet<>();
        allFields.addAll(registryMap.keySet());
        allFields.addAll(sourceMap.keySet());

        List<DatasetCompareResult.FieldCompareEntry> entries = new ArrayList<>();
        int matched = 0, missingInSource = 0, extraInSource = 0;

        for (String fieldName : allFields) {
            FieldDefinition regField = registryMap.get(fieldName);
            ColumnInfo srcCol = sourceMap.get(fieldName);

            boolean inRegistry = regField != null;
            boolean inSource = srcCol != null;

            String registryType = inRegistry
                    ? (regField.sdvType() + " " + (regField.biaoshigs() != null ? regField.biaoshigs() : "")).trim()
                    : null;
            String sourceType = inSource
                    ? formatSourceType(srcCol)
                    : null;

            boolean pkInRegistry = inRegistry && regField.primaryKey();
            boolean notNullInRegistry = inRegistry && regField.notNull();

            String status;
            if (inRegistry && inSource) {
                status = "MATCH";
                matched++;
            } else if (inRegistry && !inSource) {
                status = "MISSING_IN_SOURCE";
                missingInSource++;
            } else {
                status = "EXTRA_IN_SOURCE";
                extraInSource++;
            }

            entries.add(new DatasetCompareResult.FieldCompareEntry(
                    fieldName, inRegistry, inSource, registryType, sourceType,
                    pkInRegistry, notNullInRegistry, status));
        }

        return new DatasetCompareResult(
                dataset.shujujdm(),
                dataset.shujujmc(),
                dataset.fields().size(),
                sourceColumns.size(),
                entries,
                new DatasetCompareResult.Summary(matched, missingInSource, extraInSource)
        );
    }

    private String formatSourceType(ColumnInfo col) {
        String type = col.dataType();
        String normalized = type == null ? "" : type.trim().toUpperCase(Locale.ROOT);

        if ("TEXT".equals(normalized) || "CLOB".equals(normalized) || "NCLOB".equals(normalized)) {
            return type;
        }
        if (isCharacterType(col.jdbcType(), normalized)) {
            if (Integer.valueOf(Integer.MAX_VALUE).equals(col.columnSize())) {
                return type + "(unbounded)";
            }
            return appendLength(type, col.columnSize());
        }
        if (isDecimalType(col.jdbcType(), normalized)) {
            if (col.columnSize() == null || col.columnSize() <= 0) {
                return type;
            }
            int scale = col.decimalDigits() == null ? 0 : Math.max(col.decimalDigits(), 0);
            return type + "(" + col.columnSize() + "," + scale + ")";
        }
        if (isTimestampOrTimeType(col.jdbcType(), normalized)) {
            return col.decimalDigits() == null
                    ? type
                    : type + "(" + Math.max(col.decimalDigits(), 0) + ")";
        }
        return type;
    }

    private static String appendLength(String type, Integer length) {
        return length == null || length <= 0 ? type : type + "(" + length + ")";
    }

    private static boolean isCharacterType(Integer jdbcType, String normalized) {
        if (jdbcType != null && (jdbcType == Types.CHAR
                || jdbcType == Types.VARCHAR
                || jdbcType == Types.LONGVARCHAR
                || jdbcType == Types.NCHAR
                || jdbcType == Types.NVARCHAR
                || jdbcType == Types.LONGNVARCHAR)) {
            return true;
        }
        return normalized.contains("CHAR") || normalized.contains("VARCHAR");
    }

    private static boolean isDecimalType(Integer jdbcType, String normalized) {
        return (jdbcType != null && (jdbcType == Types.DECIMAL || jdbcType == Types.NUMERIC))
                || "DECIMAL".equals(normalized)
                || "NUMERIC".equals(normalized)
                || "NUMBER".equals(normalized);
    }

    private static boolean isTimestampOrTimeType(Integer jdbcType, String normalized) {
        return (jdbcType != null && (jdbcType == Types.TIMESTAMP
                || jdbcType == Types.TIMESTAMP_WITH_TIMEZONE
                || jdbcType == Types.TIME
                || jdbcType == Types.TIME_WITH_TIMEZONE))
                || normalized.contains("TIMESTAMP")
                || normalized.startsWith("TIME");
    }
}
