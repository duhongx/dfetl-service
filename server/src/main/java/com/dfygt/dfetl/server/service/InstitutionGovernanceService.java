package com.dfygt.dfetl.server.service;

import com.dfygt.dfetl.server.dto.InstitutionMismatchTaskDto;
import com.dfygt.dfetl.server.dto.InstitutionOrphanTaskDto;
import com.dfygt.dfetl.server.dto.InstitutionRepairReportDto;
import com.dfygt.dfetl.server.entity.Institution;
import com.dfygt.dfetl.server.repository.InstitutionRepository;
import com.dfygt.dfetl.server.repository.SyncTaskRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 机构维度数据治理服务。
 *
 * <p>对应 spec institution-management 任务 18（数据治理工具，Validates: Requirements 5.2）：
 * <ul>
 *   <li>{@link #findOrphanTasks()}：检测孤儿任务（任务未继承数据源机构）。</li>
 *   <li>{@link #findMismatchTasks()}：检测任务与数据源机构关联不一致。</li>
 *   <li>{@link #repairOrphans(boolean)}：把数据源机构回填到任务（仅 admin 调用）。</li>
 * </ul>
 *
 * <p>设计取舍：
 * <ul>
 *   <li>仅处理「任务 vs 数据源」一对二维度的失配；机构与
 *       {@code batch_task_template_source} 的失配由迁移脚本与运维 SQL 处理（见
 *       {@code design.md} Migration Plan），不在本服务范围内。</li>
 *   <li>修复仅做「孤儿回填」（NULL → 数据源值），不动 mismatch 项；后者保留人工决策权
 *       （如共享数据源场景的合法覆盖）。</li>
 *   <li>查询语句通过 {@link SyncTaskRepository#findOrphanTaskRows()} /
 *       {@link SyncTaskRepository#findMismatchTaskRows()} 一次性返回所需字段，避免 N+1。</li>
 *   <li>修复执行后 {@link org.springframework.data.jpa.repository.Modifying @Modifying}
 *       导致一级缓存与 DB 不同步，调用方应避免在同一事务内复用旧实体（见
 *       {@link #repairOrphans(boolean)} 的事务边界说明）。</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class InstitutionGovernanceService {

    private final SyncTaskRepository syncTaskRepository;
    private final InstitutionRepository institutionRepository;

    /**
     * 列出所有孤儿任务：{@code sync_task.institution_id IS NULL} 且
     * {@code source_data_source.institution_id IS NOT NULL}。
     *
     * <p>结果按 taskId 升序返回，便于运维肉眼比对与回滚。未关联数据源的任务直接排除
     * （SQL 层 INNER JOIN 已过滤），不视为孤儿。
     */
    public List<InstitutionOrphanTaskDto> findOrphanTasks() {
        List<Object[]> rows = syncTaskRepository.findOrphanTaskRows();
        if (rows.isEmpty()) {
            return Collections.emptyList();
        }
        List<InstitutionOrphanTaskDto> result = new ArrayList<>(rows.size());
        for (Object[] row : rows) {
            // 列顺序见 SyncTaskRepository.findOrphanTaskRows 的 JPQL：
            // [taskId, taskName, sourceDataSourceId, sourceDataSourceName, sourceInstitutionId]
            result.add(new InstitutionOrphanTaskDto(
                    (Long) row[0],
                    (String) row[1],
                    (Long) row[2],
                    (String) row[3],
                    (Long) row[4]
            ));
        }
        return result;
    }

    /**
     * 列出机构关联不一致的任务：双方 institution_id 都非空但不相等。
     *
     * <p>对比 {@link #findOrphanTasks()}：本方法不修复，仅生成报告。共享数据源被多个
     * 机构复用是合法场景，需要人工逐条决策；自动化批量覆盖会破坏正确数据。
     */
    public List<InstitutionMismatchTaskDto> findMismatchTasks() {
        List<Object[]> rows = syncTaskRepository.findMismatchTaskRows();
        if (rows.isEmpty()) {
            return Collections.emptyList();
        }
        // 收集所有出现过的 institutionId，一次性补齐机构名称（避免 N+1）
        Set<Long> instIds = new HashSet<>();
        for (Object[] row : rows) {
            if (row[4] != null) instIds.add((Long) row[4]);
            if (row[5] != null) instIds.add((Long) row[5]);
        }
        Map<Long, String> nameById = new HashMap<>();
        if (!instIds.isEmpty()) {
            for (Institution i : institutionRepository.findAllById(instIds)) {
                nameById.put(i.getId(), i.getName());
            }
        }

        List<InstitutionMismatchTaskDto> result = new ArrayList<>(rows.size());
        for (Object[] row : rows) {
            // 列顺序：[taskId, taskName, sourceDataSourceId, sourceDataSourceName,
            //         taskInstitutionId, sourceInstitutionId]
            Long taskInstId = (Long) row[4];
            Long sourceInstId = (Long) row[5];
            result.add(new InstitutionMismatchTaskDto(
                    (Long) row[0],
                    (String) row[1],
                    (Long) row[2],
                    (String) row[3],
                    taskInstId,
                    nameById.get(taskInstId),
                    sourceInstId,
                    nameById.get(sourceInstId)
            ));
        }
        return result;
    }

    /**
     * 一次性修复孤儿任务：把数据源 institution_id 回填到任务 institution_id。
     *
     * <p>事务边界：整个方法包裹在单个事务内，先快照 before 计数 → 执行 UPDATE →
     * 快照 after 计数。{@code dryRun=true} 时仅返回 before 列表，不调用 UPDATE。
     *
     * <p>{@code affectedRows} 来自 {@link SyncTaskRepository#repairOrphanInstitutionIds()}
     * JPQL UPDATE 的实际行数。{@code repairedTaskIds} 取 UPDATE 之前的孤儿任务 ID
     * 快照——这是 UPDATE 的目标集合的稳定来源（同事务内 SELECT 已锁定语义）。
     *
     * <p>仅 admin 调用：当前没有 RBAC 中间件，调用方（控制器）通过 URL 路径前缀
     * {@code /api/institution/admin/...} 与文档约束声明权限范围（与
     * {@code /api/admin/column-type-correction} 的现有约定一致）。后续如引入角色检查，
     * 在此处加切面或在 controller 加 {@code @PreAuthorize} 即可。
     *
     * @param dryRun true=预演（返回当前孤儿列表，不修改数据）；false=执行修复
     */
    @Transactional
    public InstitutionRepairReportDto repairOrphans(boolean dryRun) {
        // before 快照（同时作为 repairedTaskIds 的来源）
        List<InstitutionOrphanTaskDto> beforeOrphans = findOrphanTasks();
        long orphansBefore = beforeOrphans.size();
        List<Long> targetIds = new ArrayList<>(beforeOrphans.size());
        for (InstitutionOrphanTaskDto o : beforeOrphans) {
            targetIds.add(o.getTaskId());
        }

        if (dryRun) {
            log.info("repairOrphans(dryRun=true): {} orphan task(s) detected, no change applied",
                    orphansBefore);
            return new InstitutionRepairReportDto(
                    orphansBefore,
                    orphansBefore,   // dryRun 不改库，after == before
                    0,
                    targetIds,
                    true
            );
        }

        int affected = syncTaskRepository.repairOrphanInstitutionIds();

        // after 快照（理论上应为 0；并发或数据源新关联机构会导致非 0）
        long orphansAfter = syncTaskRepository.findOrphanTaskRows().size();

        log.info("repairOrphans: before={}, after={}, affectedRows={}, repairedTaskIds={}",
                orphansBefore, orphansAfter, affected, targetIds);

        return new InstitutionRepairReportDto(
                orphansBefore,
                orphansAfter,
                affected,
                targetIds,
                false
        );
    }
}
