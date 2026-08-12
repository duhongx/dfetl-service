package com.dfygt.dfetl.server.service;

import com.dfygt.dfetl.server.dto.ValidationPolicy;
import com.dfygt.dfetl.server.entity.SyncTask;
import com.dfygt.dfetl.server.entity.TaskValidationConfig;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Locale;

@Service
@RequiredArgsConstructor
public class EffectiveValidationMethodResolver {

    private final GlobalSettingsService globalSettingsService;

    public String resolveTriggeredMethod(SyncTask task, TaskValidationConfig config) {
        String method = rawConfigMethod(config);
        if (method == null) {
            method = toTaskMethod(globalSettingsService.getValidationPolicy().method());
        }
        return normalizeSupportedMethod(task, method);
    }

    public String resolveManualMethod(SyncTask task, TaskValidationConfig config, String fallbackMethod) {
        boolean configActive = config != null && Boolean.TRUE.equals(config.getEnabled());
        String method = rawConfigMethod(config);
        if (method == null && configActive) {
            method = toTaskMethod(globalSettingsService.getValidationPolicy().method());
        } else if (method == null) {
            method = fallbackMethod != null && !fallbackMethod.isBlank()
                    ? fallbackMethod
                    : toTaskMethod(globalSettingsService.getValidationPolicy().method());
        }
        return normalizeSupportedMethod(task, method);
    }

    public boolean resolveEffectiveAutoTrigger(TaskValidationConfig config) {
        ValidationPolicy policy = globalSettingsService.getValidationPolicy();
        boolean configActive = config != null && Boolean.TRUE.equals(config.getEnabled());
        if (configActive && config.getAutoTrigger() != null) {
            return Boolean.TRUE.equals(config.getAutoTrigger());
        }
        return policy.autoEnabled();
    }

    public boolean isTaskConfigActive(TaskValidationConfig config) {
        return config != null && Boolean.TRUE.equals(config.getEnabled());
    }

    public boolean resolveEffectiveEnabled(TaskValidationConfig config) {
        return isTaskConfigActive(config) || globalSettingsService.getValidationPolicy().autoEnabled();
    }

    public boolean resolveEffectiveBlockOnFail(TaskValidationConfig config) {
        if (isTaskConfigActive(config) && config.getBlockOnFail() != null) {
            return Boolean.TRUE.equals(config.getBlockOnFail());
        }
        return globalSettingsService.getValidationPolicy().failBlock();
    }

    public String resolveMethodSource(TaskValidationConfig config) {
        return isTaskConfigActive(config) && rawConfigMethod(config) != null ? "TASK" : "GLOBAL";
    }

    public String resolveAutoTriggerSource(TaskValidationConfig config) {
        return isTaskConfigActive(config) && config.getAutoTrigger() != null ? "TASK" : "GLOBAL";
    }

    public String resolveBlockOnFailSource(TaskValidationConfig config) {
        return isTaskConfigActive(config) && config.getBlockOnFail() != null ? "TASK" : "GLOBAL";
    }

    public boolean requiresChecksum(String method) {
        String normalized = normalizeMethod(method);
        return "CHECKSUM".equals(normalized)
                || "ROW_COUNT_CHECKSUM".equals(normalized)
                || "ALL".equals(normalized);
    }

    private String rawConfigMethod(TaskValidationConfig config) {
        boolean configActive = config != null && Boolean.TRUE.equals(config.getEnabled());
        if (!configActive || config.getMethod() == null || config.getMethod().isBlank()) {
            return null;
        }
        return config.getMethod();
    }

    private String toTaskMethod(String globalMethod) {
        String normalized = globalMethod == null || globalMethod.isBlank()
                ? "row_count"
                : globalMethod.trim().toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "checksum" -> "CHECKSUM";
            case "row_count_checksum", "all" -> "ROW_COUNT_CHECKSUM";
            default -> "ROW_COUNT";
        };
    }

    private String normalizeSupportedMethod(SyncTask task, String method) {
        String normalized = normalizeMethod(method);
        if (!requiresChecksum(normalized)) {
            return normalized;
        }
        if (task != null && "CUSTOM_SQL".equalsIgnoreCase(task.getSourceMode())) {
            throw new IllegalArgumentException("CUSTOM_SQL 任务不支持 " + normalized
                    + " 校验，请改用 ROW_COUNT 或表/视图源");
        }
        if (!hasChecksumKey(task)) {
            throw new IllegalArgumentException("任务缺少 splitPk/upsertKeys，不能执行 " + normalized
                    + " 校验，请配置比对键或改用 ROW_COUNT");
        }
        return normalized;
    }

    private String normalizeMethod(String method) {
        return method == null || method.isBlank()
                ? "ROW_COUNT"
                : method.trim().toUpperCase(Locale.ROOT);
    }

    private boolean hasChecksumKey(SyncTask task) {
        if (task == null) {
            return false;
        }
        if (task.getSplitPk() != null && !task.getSplitPk().isBlank()) {
            return true;
        }
        return task.getUpsertKeys() != null && task.getUpsertKeys().stream()
                .anyMatch(key -> key != null && !key.isBlank());
    }
}
