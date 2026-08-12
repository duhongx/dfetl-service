package com.dfygt.dfetl.server.common;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Runtime parser for sync task targetTableMap.
 *
 * <p>targetTableMap controls the physical Doris table used by execution,
 * validation and repair paths. Invalid JSON must fail closed instead of
 * silently falling back to the source table name.
 */
public final class TargetTableMapParser {

    private static final TypeReference<Map<String, String>> MAP_TYPE = new TypeReference<>() {};

    private TargetTableMapParser() {
    }

    public static Map<String, String> parseStrict(String json, ObjectMapper objectMapper, Long taskId) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            Map<String, String> parsed = objectMapper.readValue(json, MAP_TYPE);
            if (parsed == null || parsed.isEmpty()) {
                return Map.of();
            }
            Map<String, String> normalized = new LinkedHashMap<>();
            for (Map.Entry<String, String> entry : parsed.entrySet()) {
                String key = entry.getKey();
                String value = entry.getValue();
                if (key == null || key.isBlank()) {
                    throw new IllegalArgumentException(errorPrefix(taskId) + "targetTableMap 中源表名不能为空");
                }
                if (value == null || value.isBlank()) {
                    throw new IllegalArgumentException(errorPrefix(taskId) + "targetTableMap 中目标表名不能为空: " + key);
                }
                normalized.put(key, value);
            }
            return normalized;
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException(errorPrefix(taskId)
                    + "targetTableMap JSON 格式错误: " + e.getMessage(), e);
        }
    }

    private static String errorPrefix(Long taskId) {
        return taskId == null ? "" : "task=" + taskId + " ";
    }
}
