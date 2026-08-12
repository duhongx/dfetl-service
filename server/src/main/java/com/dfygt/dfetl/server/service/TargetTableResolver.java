package com.dfygt.dfetl.server.service;

import com.dfygt.dfetl.server.entity.SyncTask;
import com.dfygt.dfetl.server.common.TargetTableMapParser;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.Map;

/**
 * 统一解析目标表名：{@code targetTableMap[srcTable] || srcTable}，
 * 改名后仍走白名单 {@link WhereClauseBuilder#isFieldNameSafe}，
 * 最终输出统一小写（Doris {@code lower_case_table_names} 行为一致）。
 *
 * <p>抽自 ChecksumService / SnapshotDeleteService 的两份重复实现，
 * 修复两端大小写不一致导致的 Stream Load 找不到表问题。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TargetTableResolver {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final WhereClauseBuilder whereClauseBuilder;

    /**
     * @param task 同步任务（读 {@code targetTableMap}）
     * @param srcTable 源端表名 / 视图名
     * @return 目标表名（小写、已过白名单）
     * @throws IllegalArgumentException 解析后表名不通过白名单
     */
    public String resolve(SyncTask task, String srcTable) {
        if (srcTable == null || srcTable.isBlank()) {
            throw new IllegalArgumentException("srcTable 不能为空");
        }
        String tgt = srcTable;
        String mapJson = task.getTargetTableMap();
        if (mapJson != null && !mapJson.isBlank()) {
            Map<String, String> m = TargetTableMapParser.parseStrict(mapJson, OBJECT_MAPPER, task.getId());
            String mapped = m.get(srcTable);
            if (mapped != null && !mapped.isBlank()) {
                tgt = mapped;
            }
        }
        String lower = tgt.toLowerCase(Locale.ROOT);
        if (!whereClauseBuilder.isFieldNameSafe(lower)) {
            throw new IllegalArgumentException("目标表名格式非法: " + tgt);
        }
        return lower;
    }

    /**
     * 将用户在校验配置中输入的目标表名反解为源表名。
     * 无 targetTableMap 或未命中映射时，按源/目标同名处理。
     */
    public String resolveSourceForTarget(SyncTask task, String targetTable) {
        if (targetTable == null || targetTable.isBlank()) {
            throw new IllegalArgumentException("targetTable 不能为空");
        }
        String normalizedTarget = targetTable.toLowerCase(Locale.ROOT);
        if (!whereClauseBuilder.isFieldNameSafe(normalizedTarget)) {
            throw new IllegalArgumentException("目标表名格式非法: " + targetTable);
        }

        String mapJson = task.getTargetTableMap();
        if (mapJson != null && !mapJson.isBlank()) {
            Map<String, String> m = TargetTableMapParser.parseStrict(mapJson, OBJECT_MAPPER, task.getId());
            for (Map.Entry<String, String> entry : m.entrySet()) {
                String mapped = entry.getValue();
                if (mapped != null && mapped.toLowerCase(Locale.ROOT).equals(normalizedTarget)) {
                    String src = entry.getKey();
                    if (!whereClauseBuilder.isFieldNameSafe(src)) {
                        throw new IllegalArgumentException("源表名格式非法: " + src);
                    }
                    return src;
                }
            }
        }

        if (task.getViewNames() != null) {
            for (String viewName : task.getViewNames()) {
                if (viewName != null && viewName.equalsIgnoreCase(targetTable)) {
                    return viewName;
                }
            }
        }
        return targetTable;
    }
}
