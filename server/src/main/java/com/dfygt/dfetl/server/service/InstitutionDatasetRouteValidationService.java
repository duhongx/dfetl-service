package com.dfygt.dfetl.server.service;

import com.dfygt.dfetl.server.entity.DfetlField;
import com.dfygt.dfetl.server.entity.InstitutionDatasetRoute;
import com.dfygt.dfetl.server.repository.DfetlFieldRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** 对显式路由的源对象和标准字段执行只读元数据校验。 */
@Service
@RequiredArgsConstructor
public class InstitutionDatasetRouteValidationService {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final SourceDataSourceService sourceDataSourceService;
    private final DfetlFieldRepository fieldRepository;

    public Result validate(InstitutionDatasetRoute route) {
        List<SourceDataSourceService.TableInfo> matches = sourceDataSourceService
                .listTables(route.getSourceDatasourceId(), route.getSourceSchema())
                .stream()
                .filter(table -> table.tableName().equalsIgnoreCase(route.getSourceObject()))
                .toList();
        if (matches.size() != 1) {
            return result(false,
                    "源对象匹配到 " + matches.size() + " 个，要求大小写不敏感唯一命中",
                    List.of(),
                    List.of(),
                    matches.stream().map(SourceDataSourceService.TableInfo::tableName).toList());
        }

        SourceDataSourceService.TableInfo matched = matches.getFirst();
        route.setSourceObject(matched.tableName());
        route.setSourceObjectType(normalizeObjectType(matched.tableType()));
        List<SourceDataSourceService.ColumnInfo> sourceColumns = sourceDataSourceService.listColumns(
                route.getSourceDatasourceId(),
                route.getSourceSchema(),
                matched.tableName());
        List<DfetlField> standardFields = fieldRepository
                .findByDatasetIdOrderByFieldOrderAscIdAsc(route.getDatasetId())
                .stream()
                .filter(field -> "ACTIVE".equalsIgnoreCase(field.getFieldStatus()))
                .toList();

        Map<String, String> sourceByNormalized = new LinkedHashMap<>();
        Set<String> duplicateSourceColumns = new LinkedHashSet<>();
        for (SourceDataSourceService.ColumnInfo column : sourceColumns) {
            String normalized = normalize(column.columnName());
            if (sourceByNormalized.putIfAbsent(normalized, column.columnName()) != null) {
                duplicateSourceColumns.add(column.columnName());
            }
        }
        if (!duplicateSourceColumns.isEmpty()) {
            return result(false,
                    "源对象存在大小写不敏感重名字段",
                    List.of(),
                    List.copyOf(duplicateSourceColumns),
                    List.of(matched.tableName()));
        }

        Set<String> standardCodes = new LinkedHashSet<>();
        List<String> missing = new java.util.ArrayList<>();
        for (DfetlField field : standardFields) {
            String normalized = normalize(field.getFieldCode());
            standardCodes.add(normalized);
            if (!sourceByNormalized.containsKey(normalized)) {
                missing.add(field.getFieldCode());
            }
        }
        List<String> extra = sourceByNormalized.entrySet().stream()
                .filter(entry -> !standardCodes.contains(entry.getKey()))
                .map(Map.Entry::getValue)
                .toList();
        if (!missing.isEmpty()) {
            return result(false,
                    "缺少标准字段 " + missing.size() + " 个；额外字段 " + extra.size() + " 个",
                    missing,
                    extra,
                    List.of(matched.tableName()));
        }
        return result(true,
                "字段校验通过；额外字段 " + extra.size() + " 个",
                List.of(),
                extra,
                List.of(matched.tableName()));
    }

    private static Result result(
            boolean passed,
            String summary,
            List<String> missing,
            List<String> extra,
            List<String> objectMatches) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("missingFields", missing);
        details.put("extraFields", extra);
        details.put("objectMatches", objectMatches);
        try {
            return new Result(passed, summary, OBJECT_MAPPER.writeValueAsString(details));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("序列化路由校验结果失败", exception);
        }
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private static String normalizeObjectType(String value) {
        if (value == null) {
            return "VIEW";
        }
        String normalized = value.trim().toUpperCase(Locale.ROOT).replace(' ', '_');
        return "MATERIALIZED_VIEW".equals(normalized) ? normalized
                : "TABLE".equals(normalized) ? "TABLE" : "VIEW";
    }

    public record Result(boolean passed, String summary, String detailsJson) {
    }
}
