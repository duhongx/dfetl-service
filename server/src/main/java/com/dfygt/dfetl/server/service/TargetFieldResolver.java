package com.dfygt.dfetl.server.service;

import com.dfygt.dfetl.server.entity.SyncTask;
import com.dfygt.dfetl.server.entity.TaskViewConfig;
import com.dfygt.dfetl.server.repository.TaskViewConfigRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * 解析源字段在目标端 Doris 表中的字段名。
 *
 * <p>当前项目主执行链路默认把源字段 alias 为小写并写入 Doris；如果存在
 * {@code task_view_config.field_mappings}，则优先使用显式字段映射。显式映射
 * 不完整时不静默回退，避免目标端 ROW_COUNT/CHECKSUM 使用错误字段。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TargetFieldResolver {

    static final String UNSUPPORTED_RENAME_MESSAGE =
            "当前版本尚未支持 sourceField -> targetField 字段重命名的端到端同步与校验。"
                    + "请先使用源字段小写同名目标列，或等待字段映射端到端能力完善。";

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper()
            .configure(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    private static final TypeReference<List<FieldMappingEntry>> MAPPING_LIST_TYPE = new TypeReference<>() {};

    final TaskViewConfigRepository configRepository;
    private final WhereClauseBuilder whereClauseBuilder;

    public String resolveTargetColumn(SyncTask task, String sourceTable, String sourceColumn) {
        String source = requireSafeSourceColumn(sourceColumn);
        Optional<TaskViewConfig> config = findConfig(task, sourceTable);
        if (config.isEmpty() || config.get().getFieldMappings() == null || config.get().getFieldMappings().isBlank()) {
            return fallbackTargetColumn(source);
        }

        List<FieldMappingEntry> mappings = parseMappings(task, sourceTable, config.get().getFieldMappings());
        boolean hasExplicitSourceMappings = mappings.stream().anyMatch(FieldMappingEntry::isBusinessSourceMapping);
        if (!hasExplicitSourceMappings) {
            return fallbackTargetColumn(source);
        }

        for (FieldMappingEntry mapping : mappings) {
            if (!mapping.isBusinessSourceMapping()) {
                continue;
            }
            if (!source.equalsIgnoreCase(mapping.sourceField().trim())) {
                continue;
            }
            if (!mapping.includedByTask()) {
                throw new IllegalStateException("字段映射中源字段未勾选，无法确认目标端字段: " + source);
            }
            String target = mapping.targetField() == null ? "" : mapping.targetField().trim();
            if (target.isBlank()) {
                throw new IllegalStateException("字段映射中源字段缺少目标字段: " + source);
            }
            return normalizeAndValidateTargetColumn(target);
        }

        throw new IllegalStateException("字段映射中找不到源字段的目标字段: " + source);
    }

    public List<String> resolveTargetColumns(SyncTask task, String sourceTable, List<String> sourceColumns) {
        if (sourceColumns == null || sourceColumns.isEmpty()) {
            return List.of();
        }
        return sourceColumns.stream()
                .map(sourceColumn -> resolveTargetColumn(task, sourceTable, sourceColumn))
                .toList();
    }

    public String resolveTargetColumnSameNameOnly(
            SyncTask task, String sourceTable, String sourceColumn, String purpose) {
        String source = requireSafeSourceColumn(sourceColumn);
        String target = resolveTargetColumn(task, sourceTable, source);
        String expected = fallbackTargetColumn(source);
        if (!expected.equals(target)) {
            throw unsupportedRename(source, target, purpose);
        }
        return target;
    }

    public List<String> resolveTargetColumnsSameNameOnly(
            SyncTask task, String sourceTable, List<String> sourceColumns, String purpose) {
        if (sourceColumns == null || sourceColumns.isEmpty()) {
            return List.of();
        }
        return sourceColumns.stream()
                .map(sourceColumn -> resolveTargetColumnSameNameOnly(task, sourceTable, sourceColumn, purpose))
                .toList();
    }

    public void assertNoUnsupportedRename(
            SyncTask task, String sourceTable, List<String> sourceColumns, String purpose) {
        resolveTargetColumnsSameNameOnly(task, sourceTable, sourceColumns, purpose);
    }

    public boolean hasExplicitRename(SyncTask task, String sourceTable) {
        Optional<TaskViewConfig> config = findConfig(task, sourceTable);
        if (config.isEmpty() || config.get().getFieldMappings() == null || config.get().getFieldMappings().isBlank()) {
            return false;
        }
        List<FieldMappingEntry> mappings = parseMappings(task, sourceTable, config.get().getFieldMappings());
        for (FieldMappingEntry mapping : mappings) {
            if (!mapping.isBusinessSourceMapping()) {
                continue;
            }
            String source = requireSafeSourceColumn(mapping.sourceField());
            String target = mapping.targetField() == null ? "" : mapping.targetField().trim();
            if (target.isBlank()) {
                throw new IllegalStateException("字段映射中源字段缺少目标字段: " + source);
            }
            if (!fallbackTargetColumn(source).equals(normalizeAndValidateTargetColumn(target))) {
                return true;
            }
        }
        return false;
    }

    private String requireSafeSourceColumn(String sourceColumn) {
        if (sourceColumn == null || sourceColumn.isBlank()) {
            throw new IllegalArgumentException("源字段名不能为空");
        }
        String source = sourceColumn.trim();
        if (!whereClauseBuilder.isFieldNameSafe(source)) {
            throw new IllegalArgumentException("源字段名格式非法: " + source);
        }
        return source;
    }

    private Optional<TaskViewConfig> findConfig(SyncTask task, String sourceTable) {
        if (task == null || task.getId() == null || sourceTable == null || sourceTable.isBlank()) {
            return Optional.empty();
        }
        try {
            Optional<TaskViewConfig> exact = configRepository.findByTaskIdAndViewName(task.getId(), sourceTable);
            if (exact != null && exact.isPresent()) {
                return exact;
            }
            List<TaskViewConfig> configs = configRepository.findByTaskId(task.getId());
            if (configs == null || configs.isEmpty()) {
                return Optional.empty();
            }
            return configs.stream()
                    .filter(config -> config.getViewName() != null
                            && config.getViewName().equalsIgnoreCase(sourceTable))
                    .findFirst();
        } catch (Exception e) {
            throw new IllegalStateException("字段映射配置读取失败，无法确认目标端字段: taskId="
                    + task.getId() + ", table=" + sourceTable + ": " + e.getMessage(), e);
        }
    }

    private List<FieldMappingEntry> parseMappings(SyncTask task, String sourceTable, String json) {
        try {
            return OBJECT_MAPPER.readValue(json, MAPPING_LIST_TYPE);
        } catch (Exception e) {
            throw new IllegalArgumentException("字段映射 JSON 解析失败，taskId="
                    + (task == null ? null : task.getId()) + ", table=" + sourceTable + ": " + e.getMessage(), e);
        }
    }

    private String fallbackTargetColumn(String source) {
        return normalizeAndValidateTargetColumn(source);
    }

    private String normalizeAndValidateTargetColumn(String targetColumn) {
        String target = targetColumn.trim().toLowerCase(Locale.ROOT);
        if (!whereClauseBuilder.isFieldNameSafe(target)) {
            throw new IllegalArgumentException("目标字段名格式非法: " + targetColumn);
        }
        return target;
    }

    private UnsupportedOperationException unsupportedRename(String source, String target, String purpose) {
        String context = purpose == null || purpose.isBlank() ? "" : purpose + "：";
        return new UnsupportedOperationException(context + UNSUPPORTED_RENAME_MESSAGE
                + " sourceField=" + source + ", targetField=" + target);
    }

    private record FieldMappingEntry(
            String sourceField,
            String targetField,
            Boolean checked,
            Boolean included,
            Boolean isExtra
    ) {
        boolean includedByTask() {
            return included != null ? included : !Boolean.FALSE.equals(checked);
        }

        boolean isBusinessSourceMapping() {
            return !Boolean.TRUE.equals(isExtra)
                    && sourceField != null
                    && !sourceField.isBlank()
                    && !"—".equals(sourceField.trim());
        }
    }
}
