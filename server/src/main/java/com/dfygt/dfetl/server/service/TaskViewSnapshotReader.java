package com.dfygt.dfetl.server.service;

import com.dfygt.dfetl.server.entity.TaskViewConfig;
import com.dfygt.dfetl.server.medical.precheck.MedicalDatasetFieldOverride;
import com.dfygt.dfetl.server.repository.TaskViewConfigRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** 读取任务创建时固定下来的字段映射；运行期不再反查系统配置。 */
@Service
@RequiredArgsConstructor
public class TaskViewSnapshotReader {

    private final TaskViewConfigRepository repository;
    private final ObjectMapper objectMapper;

    public List<Map<String, Object>> rows(Long taskId) {
        if (taskId == null) return List.of();
        return repository.findByTaskId(taskId).stream().findFirst()
                .map(TaskViewConfig::getFieldMappings)
                .map(this::parse)
                .orElse(List.of());
    }

    public Map<String, String> fieldMapping(Long taskId) {
        Map<String, String> result = new LinkedHashMap<>();
        for (Map<String, Object> row : rows(taskId)) {
            if (!included(row)) continue;
            String source = text(row.get("sourceField"));
            String target = text(row.get("targetField"));
            put(result, text(row.get("fieldCode")), source);
            put(result, target, source);
        }
        return result;
    }

    public List<MedicalDatasetFieldOverride> fieldOverrides(Long taskId) {
        List<MedicalDatasetFieldOverride> result = new ArrayList<>();
        for (Map<String, Object> row : rows(taskId)) {
            if (!included(row)) continue;
            result.add(new MedicalDatasetFieldOverride(
                    first(text(row.get("fieldCode")), text(row.get("targetField"))),
                    text(row.get("fieldName")),
                    text(row.get("sourceField")),
                    text(row.get("targetField")),
                    text(row.get("targetType")),
                    text(row.get("standardType")),
                    text(row.get("standardFormat")),
                    text(row.get("standardVersion")),
                    bool(row.get("primaryKey")),
                    bool(row.get("upsertKey")),
                    bool(row.get("requiredByStandard")),
                    text(row.get("valueDomainCode")),
                    text(row.get("valueDomainSource")),
                    text(row.get("valueDomainVersion")),
                    text(row.get("valueDomainMode"))));
        }
        return result;
    }

    private List<Map<String, Object>> parse(String json) {
        if (json == null || json.isBlank()) return List.of();
        try { return objectMapper.readValue(json, new TypeReference<List<Map<String, Object>>>() {}); }
        catch (Exception e) { throw new IllegalStateException("任务字段映射快照 JSON 无效", e); }
    }

    private static boolean included(Map<String, Object> row) {
        Object value = row.containsKey("included") ? row.get("included") : row.get("checked");
        return value == null || Boolean.parseBoolean(String.valueOf(value));
    }

    private static Boolean bool(Object value) { return value == null ? null : Boolean.valueOf(String.valueOf(value)); }
    private static String text(Object value) { return value == null || String.valueOf(value).isBlank() ? null : String.valueOf(value); }
    private static String first(String first, String second) { return first == null ? second : first; }
    private static void put(Map<String, String> map, String key, String value) {
        if (key == null || value == null) return;
        map.putIfAbsent(key, value);
        map.putIfAbsent(key.toUpperCase(Locale.ROOT), value);
        map.putIfAbsent(key.toLowerCase(Locale.ROOT), value);
    }
}
