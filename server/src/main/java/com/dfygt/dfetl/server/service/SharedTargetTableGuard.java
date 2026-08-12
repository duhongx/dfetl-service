package com.dfygt.dfetl.server.service;

import com.dfygt.dfetl.server.common.TargetTableMapParser;
import com.dfygt.dfetl.server.entity.SyncTask;
import com.dfygt.dfetl.server.repository.SyncTaskRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 防止任务级 TRUNCATE 清空共享 Doris 物理表。
 *
 * <p>{@code _etl_job_id} 只能隔离行级读写，SeaTunnel 的
 * {@code data_save_mode=DROP_DATA} 作用于整张物理表。该守卫必须同时在保存期和
 * 运行期调用，既拦截新配置，也保护历史脏配置。
 */
@Service
@RequiredArgsConstructor
public class SharedTargetTableGuard {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final SyncTaskRepository repository;

    public void assertTruncateSafe(SyncTask task) {
        if (task == null) {
            throw new IllegalArgumentException("同步任务不能为空");
        }
        assertTruncateSafe(
                task.getId(),
                task.getTargetDataSourceId(),
                task.getViewNames(),
                task.getTargetTableMap(),
                task.getSyncMode());
    }

    /**
     * 保护 recollect 等显式 TRUNCATE/DROP_RECREATE 操作；该入口与任务当前 syncMode 无关。
     */
    public void assertDestructiveOperationSafe(SyncTask task) {
        if (task == null) {
            throw new IllegalArgumentException("同步任务不能为空");
        }
        assertExclusiveTargets(
                task.getId(),
                task.getTargetDataSourceId(),
                task.getViewNames(),
                task.getTargetTableMap());
    }

    public void assertTruncateSafe(Long currentTaskId,
                                   Long targetDataSourceId,
                                   List<String> sourceObjects,
                                   String targetTableMap,
                                   String syncMode) {
        if (!"TRUNCATE".equalsIgnoreCase(syncMode)) {
            return;
        }
        assertExclusiveTargets(currentTaskId, targetDataSourceId, sourceObjects, targetTableMap);
    }

    private void assertExclusiveTargets(Long currentTaskId,
                                        Long targetDataSourceId,
                                        List<String> sourceObjects,
                                        String targetTableMap) {
        if (targetDataSourceId == null) {
            throw new IllegalArgumentException("破坏性目标表操作缺少 targetDataSourceId");
        }

        List<String> resolved = resolveTargets(sourceObjects, targetTableMap, currentTaskId);
        Set<String> candidateTargets = new HashSet<>(resolved);
        if (candidateTargets.size() != resolved.size()) {
            throw new IllegalArgumentException(
                    "破坏性操作不允许多个源对象映射到同一目标物理表: " + candidateTargets);
        }

        List<SyncTask> peers = repository.findByTargetDataSourceId(targetDataSourceId);
        if (peers == null) {
            peers = List.of();
        }
        for (SyncTask peer : peers) {
            if (peer == null || (currentTaskId != null && currentTaskId.equals(peer.getId()))) {
                continue;
            }
            Set<String> peerTargets = new HashSet<>(resolveTargets(
                    peer.getViewNames(), peer.getTargetTableMap(), peer.getId()));
            Set<String> overlap = new HashSet<>(candidateTargets);
            overlap.retainAll(peerTargets);
            if (!overlap.isEmpty()) {
                throw new IllegalArgumentException(
                        "破坏性操作会清空共享目标物理表 " + overlap
                                + "，该表已被任务 " + peer.getId()
                                + " 使用；请改用 UPSERT/APPEND 或为任务配置独立目标表");
            }
        }
    }

    private List<String> resolveTargets(List<String> sourceObjects, String targetTableMap, Long taskId) {
        if (sourceObjects == null || sourceObjects.isEmpty()) {
            throw new IllegalArgumentException("TRUNCATE 任务必须至少包含一个源对象");
        }
        Map<String, String> mappings = targetTableMap == null || targetTableMap.isBlank()
                ? Map.of()
                : TargetTableMapParser.parseStrict(targetTableMap, OBJECT_MAPPER, taskId);
        List<String> targets = new ArrayList<>();
        for (String sourceObject : sourceObjects) {
            if (sourceObject == null || sourceObject.isBlank()) {
                throw new IllegalArgumentException("TRUNCATE 任务包含空源对象");
            }
            String mapped = mappings.getOrDefault(sourceObject, sourceObject);
            if (mapped == null || mapped.isBlank()) {
                throw new IllegalArgumentException("TRUNCATE 任务目标表名不能为空: " + sourceObject);
            }
            targets.add(mapped.trim().toLowerCase(Locale.ROOT));
        }
        return targets;
    }
}
