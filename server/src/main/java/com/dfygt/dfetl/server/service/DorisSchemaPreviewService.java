package com.dfygt.dfetl.server.service;

import com.dfygt.dfetl.server.dto.DorisSchemaPreviewField;
import com.dfygt.dfetl.server.dto.DorisSchemaPreviewIssue;
import com.dfygt.dfetl.server.dto.DorisSchemaPreviewRequest;
import com.dfygt.dfetl.server.dto.DorisSchemaPreviewResponse;
import com.dfygt.dfetl.server.engine.doris.DorisTypeMappingPolicy;
import com.dfygt.dfetl.server.engine.doris.DorisTypeMappingRuleService;
import com.dfygt.dfetl.server.entity.SourceDataSource;
import com.dfygt.dfetl.server.repository.SourceDataSourceRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class DorisSchemaPreviewService {

    private final SourceDataSourceService sourceDataSourceService;
    private final SourceDataSourceRepository sourceDataSourceRepository;
    private final DorisTypeMappingRuleService mappingRuleService;

    public DorisSchemaPreviewResponse preview(DorisSchemaPreviewRequest request) {
        if (request == null || request.getSourceDataSourceId() == null) {
            throw new IllegalArgumentException("请选择源数据源");
        }
        String dialect = sourceDataSourceRepository.findById(request.getSourceDataSourceId())
                .map(SourceDataSource::getType)
                .orElse("");
        String sourceObject = resolveSourceObject(request);
        List<SourceDataSourceService.ColumnInfo> columns = loadColumns(request);
        if (columns.isEmpty()) {
            throw new IllegalArgumentException("源端字段列表为空，无法预览 Doris schema");
        }

        DorisSchemaPreviewResponse response = new DorisSchemaPreviewResponse();
        response.setSourceDialect(dialect);
        response.setSourceObject(sourceObject);
        Set<String> columnNames = new HashSet<>();
        // 检测字段名大小写折叠冲突：记录每个 lowercase key 对应的原始字段名列表
        Map<String, List<String>> lowerToOriginals = new HashMap<>();
        for (SourceDataSourceService.ColumnInfo column : columns) {
            if (column.columnName() != null) {
                String lower = column.columnName().toLowerCase(Locale.ROOT);
                lowerToOriginals.computeIfAbsent(lower, k -> new ArrayList<>()).add(column.columnName());
                columnNames.add(lower);
            }
            response.getFields().add(toField(dialect, column));
        }
        // 如果存在折叠后重复的字段名，抛出明确错误
        List<String> conflicts = lowerToOriginals.entrySet().stream()
                .filter(e -> e.getValue().size() > 1)
                .map(e -> e.getValue().stream().collect(Collectors.joining(", ", "[", "]")) + " → " + e.getKey())
                .collect(Collectors.toList());
        if (!conflicts.isEmpty()) {
            throw new IllegalArgumentException(
                    "源端字段存在大小写冲突，Doris 折叠后将产生重复列名: " + String.join("; ", conflicts));
        }
        validateTaskLevel(request, columnNames, response);
        return response;
    }

    private List<SourceDataSourceService.ColumnInfo> loadColumns(DorisSchemaPreviewRequest request) {
        if ("CUSTOM_SQL".equalsIgnoreCase(request.getSourceMode())) {
            if (request.getCustomSql() == null || request.getCustomSql().isBlank()) {
                throw new IllegalArgumentException("CUSTOM_SQL 预览需要填写 SQL");
            }
            return sourceDataSourceService.listCustomSqlColumns(request.getSourceDataSourceId(), request.getCustomSql());
        }
        String viewName = resolveSourceObject(request);
        if (request.getSourceSchema() == null || request.getSourceSchema().isBlank()) {
            throw new IllegalArgumentException("TABLE_VIEW 预览需要选择 schema");
        }
        if (viewName == null || viewName.isBlank()) {
            throw new IllegalArgumentException("TABLE_VIEW 预览需要选择视图");
        }
        return sourceDataSourceService.listColumns(request.getSourceDataSourceId(), request.getSourceSchema(), viewName);
    }

    private String resolveSourceObject(DorisSchemaPreviewRequest request) {
        if ("CUSTOM_SQL".equalsIgnoreCase(request.getSourceMode())) {
            return request.getCustomSqlName() == null || request.getCustomSqlName().isBlank()
                    ? "custom_sql"
                    : request.getCustomSqlName();
        }
        if (request.getViewName() != null && !request.getViewName().isBlank()) {
            return request.getViewName();
        }
        if (request.getViewNames() != null && !request.getViewNames().isEmpty()) {
            return request.getViewNames().get(0);
        }
        return "";
    }

    private DorisSchemaPreviewField toField(String dialect, SourceDataSourceService.ColumnInfo column) {
        var mapping = mappingRuleService.recommend(
                DorisTypeMappingPolicy.SourceTypeDescriptor.fromColumn(dialect, column, true));
        DorisSchemaPreviewField field = new DorisSchemaPreviewField();
        field.setSourceField(column.columnName());
        field.setSourceType(column.dataType());
        field.setDorisField(column.columnName() == null ? "" : column.columnName().toLowerCase(Locale.ROOT));
        field.setRecommendedDorisType(mapping.recommendedDorisType());
        field.setCompatibilityLevel(mapping.compatibilityLevel().name());
        field.setReason(mapping.reason());
        field.setNullable(column.nullable());
        field.setPrecision(column.columnSize());
        field.setScale(column.decimalDigits());
        field.setLength(column.columnSize());
        return field;
    }

    private void validateTaskLevel(
            DorisSchemaPreviewRequest request,
            Set<String> columnsLower,
            DorisSchemaPreviewResponse response) {
        requireIfConfigured(columnsLower, request.getIncrementalField(), "INCREMENTAL_FIELD_MISSING",
                "增量字段在源视图字段中不存在", response);
        requireIfConfigured(columnsLower, request.getSoftDeleteField(), "SOFT_DELETE_FIELD_MISSING",
                "softDeleteField 在源视图字段中不存在", response);
        requireIfConfigured(columnsLower, request.getSequenceCol(), "SEQUENCE_COLUMN_MISSING",
                "sequenceCol 在源视图字段中不存在", response);
        if (request.getUpsertKeys() != null) {
            for (String key : request.getUpsertKeys()) {
                requireIfConfigured(columnsLower, key, "UPSERT_KEY_MISSING",
                        "业务 key 在源视图字段中不存在", response);
            }
        }

        boolean hasKey = request.getUpsertKeys() != null
                && request.getUpsertKeys().stream().anyMatch(k -> k != null && !k.isBlank());
        boolean uniqueRequested = "UNIQUE_KEY".equalsIgnoreCase(request.getDorisTableModel())
                || Boolean.TRUE.equals(request.getEnableDorisMerge());
        if (uniqueRequested && !hasKey) {
            response.getIssues().add(DorisSchemaPreviewIssue.of(
                    "BUSINESS_KEY_REQUIRED",
                    "FAIL",
                    "无业务 key 不能创建 UNIQUE_KEY/MOW；请配置 upsertKeys，或使用 DUPLICATE_KEY + ROW_COUNT",
                    null));
        }
        String validation = request.getValidationMethod();
        if (!hasKey && validation != null
                && ("CHECKSUM".equalsIgnoreCase(validation)
                || "ROW_COUNT_CHECKSUM".equalsIgnoreCase(validation)
                || "DIFF".equalsIgnoreCase(validation))) {
            response.getIssues().add(DorisSchemaPreviewIssue.of(
                    "VALIDATION_KEY_REQUIRED",
                    "FAIL",
                    "无业务 key 只能使用 ROW_COUNT 校验，不能使用 CHECKSUM / Diff",
                    null));
        }
        if ("CUSTOM_SQL".equalsIgnoreCase(request.getSourceMode())
                && validation != null
                && !"ROW_COUNT".equalsIgnoreCase(validation)) {
            response.getIssues().add(DorisSchemaPreviewIssue.of(
                    "CUSTOM_SQL_ROW_COUNT_ONLY",
                    "FAIL",
                    "CUSTOM_SQL 只支持 ROW_COUNT 校验",
                    null));
        }
    }

    private void requireIfConfigured(
            Set<String> columnsLower,
            String field,
            String code,
            String message,
            DorisSchemaPreviewResponse response) {
        if (field == null || field.isBlank()) return;
        if (!columnsLower.contains(field.toLowerCase(Locale.ROOT))) {
            response.getIssues().add(DorisSchemaPreviewIssue.of(code, "FAIL", message + ": " + field, field));
        }
    }
}
