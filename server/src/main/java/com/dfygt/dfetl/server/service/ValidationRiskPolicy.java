package com.dfygt.dfetl.server.service;

import com.dfygt.dfetl.server.dto.TaskValidationConfigDto;
import com.dfygt.dfetl.server.entity.SyncTask;
import com.dfygt.dfetl.server.repository.SyncTaskRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class ValidationRiskPolicy {

    private final SyncTaskRepository syncTaskRepo;
    private final GlobalSettingsService globalSettingsService;
    private final TargetTableResolver targetTableResolver;

    public void validate(Long taskId, TaskValidationConfigDto dto) {
        if (dto == null) {
            return;
        }
        if (Boolean.FALSE.equals(dto.getEnabled())) {
            return;
        }
        boolean autoRepair = Boolean.TRUE.equals(dto.getAutoRepair());
        String method = resolveEffectiveMethod(dto.getMethod());
        validateSupportedMethod(method);

        if (autoRepair && "ROW_COUNT".equals(method)) {
            throw new IllegalArgumentException("ROW_COUNT 校验不能直接开启 autoRepair：行数差异无法定位具体行，请改用 CHECKSUM 或 PK_DIFF");
        }

        SyncTask task = syncTaskRepo.findById(taskId).orElse(null);
        if (task == null) {
            return;
        }
        validateTargetTables(task, dto.getTargetTables());

        if (isAppendWriteMode(task) && isBlockOnFailEnabled(dto)) {
            throw new IllegalArgumentException("APPEND syncMode 不能开启 blockOnFail："
                    + "非幂等追加写入在 Gate 阻断后重跑会重复插入，请改用 UPSERT 或关闭 blockOnFail");
        }
        if (isCustomSql(task) && requiresChecksum(method)) {
            throw new IllegalArgumentException("CUSTOM_SQL 模式暂不支持 CHECKSUM / ROW_COUNT_CHECKSUM 校验，"
                    + "请使用 ROW_COUNT 或改为表/视图源");
        }
        if (requiresChecksum(method) && !isSingleTableTask(task)) {
            throw new IllegalArgumentException("CHECKSUM / ROW_COUNT_CHECKSUM 当前仅支持单表任务，"
                    + "多表任务请拆分任务或使用 ROW_COUNT");
        }
        if (requiresChecksum(method) && !hasChecksumKey(task)) {
            throw new IllegalArgumentException("CHECKSUM 校验需要配置 splitPk 或 upsertKeys 作为行级比对主键，"
                    + "否则无法定位 missing/update/extra 差异");
        }
        if (autoRepair && (task.getUpsertKeys() == null || task.getUpsertKeys().stream()
                .noneMatch(key -> key != null && !key.isBlank()))) {
            throw new IllegalArgumentException("CHECKSUM autoRepair 需要配置 upsertKeys 作为业务修复主键；"
                    + "splitPk 仅作为校验/分片键时不能自动修复");
        }

        boolean isView = "VIEW".equalsIgnoreCase(task.getSourceObjectType())
                || "MATERIALIZED_VIEW".equalsIgnoreCase(task.getSourceObjectType());
        if (autoRepair && isView && !Boolean.TRUE.equals(dto.getForceAllow())
                && !globalSettingsService.isViewAutoRepairAllowed()) {
            throw new IllegalArgumentException("视图源默认禁止 autoRepair，请在系统设置 → 校验策略中开启「允许视图源自动修复」，或在任务级设置 forceAllow=true");
        }

        String tableModel = task.getDorisTableModel() == null ? "UNIQUE_KEY" : task.getDorisTableModel().toUpperCase();
        if (autoRepair && "DUPLICATE_KEY".equals(tableModel)) {
            throw new IllegalArgumentException("DUPLICATE KEY 表模型禁止 autoRepair：重复插入会导致目标行翻倍");
        }
        if ("AGGREGATE_KEY".equals(tableModel) && requiresChecksum(method)) {
            throw new IllegalArgumentException("AGGREGATE_KEY 表模型与 " + method
                    + " 校验不兼容：聚合后行 hash 无意义，请改用 ROW_COUNT");
        }
    }

    private boolean isCustomSql(SyncTask task) {
        return task != null && "CUSTOM_SQL".equalsIgnoreCase(task.getSourceMode());
    }

    private boolean isAppendWriteMode(SyncTask task) {
        return task != null
                && task.getSyncMode() != null
                && "APPEND".equalsIgnoreCase(task.getSyncMode().trim());
    }

    private boolean requiresChecksum(String method) {
        return "CHECKSUM".equals(method)
                || "ROW_COUNT_CHECKSUM".equals(method);
    }

    private boolean isSingleTableTask(SyncTask task) {
        return task != null
                && task.getViewNames() != null
                && task.getViewNames().stream()
                .filter(table -> table != null && !table.isBlank())
                .limit(2)
                .count() == 1;
    }

    private void validateSupportedMethod(String method) {
        if ("ROW_COUNT".equals(method) || "CHECKSUM".equals(method) || "ROW_COUNT_CHECKSUM".equals(method)) {
            return;
        }
        throw new IllegalArgumentException("validation method 仅支持 ROW_COUNT / CHECKSUM / ROW_COUNT_CHECKSUM，当前值: "
                + method);
    }

    private String resolveEffectiveMethod(String taskMethod) {
        if (taskMethod != null && !taskMethod.isBlank()) {
            return taskMethod.trim().toUpperCase(Locale.ROOT);
        }
        return toTaskMethod(globalSettingsService.getValidationPolicy().method());
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

    private boolean isBlockOnFailEnabled(TaskValidationConfigDto dto) {
        if (dto.getBlockOnFail() != null) {
            return Boolean.TRUE.equals(dto.getBlockOnFail());
        }
        return globalSettingsService.getValidationPolicy().failBlock();
    }

    private void validateTargetTables(SyncTask task, List<String> targetTables) {
        if (targetTables == null || targetTables.isEmpty()) {
            return;
        }
        Set<String> allowed = new LinkedHashSet<>();
        if (task.getViewNames() != null) {
            for (String sourceTable : task.getViewNames()) {
                if (sourceTable == null || sourceTable.isBlank()) {
                    continue;
                }
                allowed.add(normalizeTableName(sourceTable));
                allowed.add(normalizeTableName(targetTableResolver.resolve(task, sourceTable)));
            }
        }
        List<String> invalid = targetTables.stream()
                .filter(t -> t != null && !t.isBlank())
                .filter(t -> !allowed.contains(normalizeTableName(t)))
                .toList();
        if (!invalid.isEmpty()) {
            throw new IllegalArgumentException("targetTables 必须属于当前任务表范围，非法值: " + invalid);
        }
    }

    private String normalizeTableName(String tableName) {
        return tableName == null ? "" : tableName.trim().toLowerCase(Locale.ROOT);
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
