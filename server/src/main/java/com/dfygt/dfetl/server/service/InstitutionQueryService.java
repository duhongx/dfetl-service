package com.dfygt.dfetl.server.service;

import com.dfygt.dfetl.server.dto.InstitutionDto;
import com.dfygt.dfetl.server.dto.InstitutionTargetRowCountDto;
import com.dfygt.dfetl.server.dto.InstitutionWithStatsDto;
import com.dfygt.dfetl.server.dto.SourceDataSourceDto;
import com.dfygt.dfetl.server.dto.SyncTaskDto;
import com.dfygt.dfetl.server.entity.Institution;
import com.dfygt.dfetl.server.entity.SyncTask;
import com.dfygt.dfetl.server.entity.TargetDataSource;
import com.dfygt.dfetl.server.repository.InstitutionRepository;
import com.dfygt.dfetl.server.repository.SourceDataSourceRepository;
import com.dfygt.dfetl.server.repository.SyncTaskRepository;
import com.dfygt.dfetl.server.repository.TargetDataSourceRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;

/**
 * 机构维度跨表聚合查询服务。
 *
 * <p>核心场景：
 * <ul>
 *   <li>按 Doris 目标表查贡献机构（{@link #listSourceInstitutionsByTargetTable(String)}）：
 *       回答「{@code lis_check} 表里有哪些医院的数据」。</li>
 *   <li>按机构查同步任务（{@link #listTasksByInstitution(Long, boolean)}）：委托
 *       {@link SyncTaskService#listByInstitution(Long, boolean)}，逻辑细节见对应方法。</li>
 *   <li>按机构查数据源（{@link #listDatasourcesByInstitution(Long)}）：委托
 *       {@link SourceDataSourceService#findByInstitutionId(Long)}。</li>
 *   <li>机构 + 关联资产统计（{@link #getInstitutionWithStats(Long)}）：用于机构管理页
 *       的列表行，附加数据源数 / 任务数 / 最近同步时间 / 状态摘要。</li>
 * </ul>
 *
 * <p>{@link #listSourceInstitutionsByTargetTable(String)} 的实现说明：
 * <ul>
 *   <li>目标表名存储于 {@code sync_task.target_table_map} JSON 列（{@code {"src":"tgt"}}），
 *       缺省时回退到源表名（与 {@link TargetTableResolver} 行为一致）。</li>
 *   <li>当前阶段 sync_task 表数据规模在万级以内，先实现内存过滤；DB 表规模继续增长后
 *       再考虑改为 PostgreSQL JSONB 查询或物化目标表索引（详见
 *       {@code design.md} Performance Considerations）。</li>
 *   <li>命中条件：JSON 任意 value 等于 {@code targetTable}（lower-case 比较）；
 *       JSON 为空或解析失败时，回退到 {@code viewNames} 中是否存在该表名。</li>
 * </ul>
 *
 * <p>对应 {@code design.md} Property 8「按目标表查机构的等价性」
 * （Validates: Requirements 4.1, 4.3）。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class InstitutionQueryService {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final TypeReference<Map<String, String>> MAP_TYPE = new TypeReference<>() {};

    /**
     * spec institution-management 任务 15.2 - 分页/限流默认值。
     *
     * <p>所有未显式分页的调用统一在服务层 clamp 到 {@link #DEFAULT_PAGE_SIZE}（默认 50）；
     * 显式传入 size 时上限为 {@link #MAX_PAGE_SIZE}（500），防止前端误传超大 size 阻塞 DB。
     */
    public static final int DEFAULT_PAGE_SIZE = 50;
    public static final int MAX_PAGE_SIZE = 500;

    private final InstitutionRepository institutionRepository;
    private final SyncTaskRepository syncTaskRepository;
    private final SourceDataSourceRepository sourceDataSourceRepository;
    private final SyncTaskService syncTaskService;
    private final SourceDataSourceService sourceDataSourceService;
    private final TargetDataSourceRepository targetDataSourceRepository;
    private final InstitutionRowCountQuery rowCountQuery;

    // ── 业务查询：按目标表查贡献机构 ────────────────────────────────────────

    /**
     * 列出贡献到指定 Doris 目标表的所有机构，并附带任务数 / 最近同步时间 / 状态摘要。
     *
     * <p>实现：扫描全部 sync_task → 过滤命中 {@code targetTable} 的任务 →
     * 按 {@code institutionId} 分组聚合；{@code institutionId} 为 null 的任务被丢弃
     * （前端可在视图层用「未关联机构」补充展示，本服务仅返回有归属的机构）。
     *
     * <p>spec institution-management 任务 15.2：默认 size={@link #DEFAULT_PAGE_SIZE}，
     * 防止单次返回过多机构造成大查询阻塞。本方法等价于
     * {@code listSourceInstitutionsByTargetTable(targetTable, 0, MAX_PAGE_SIZE)}，
     * 即对结果做硬上限 {@link #MAX_PAGE_SIZE} 截断；调用方需要更小分页时改用
     * {@link #listSourceInstitutionsByTargetTable(String, int, int)}。
     *
     * @param targetTable Doris 目标表名（不区分大小写；空白参数返回空列表）
     * @return 按机构 id 升序的列表（最多 {@link #MAX_PAGE_SIZE} 条）；每个 DTO 含 institution
     *         基础字段、syncTaskCount、lastSyncTime、statusSummary；{@code datasourceCount}
     *         在该方法语义下不适用，置为 0 由前端按需展示。
     */
    public List<InstitutionWithStatsDto> listSourceInstitutionsByTargetTable(String targetTable) {
        return listSourceInstitutionsByTargetTable(targetTable, 0, MAX_PAGE_SIZE);
    }

    /**
     * 分页变体：spec institution-management 任务 15.2。
     *
     * <p>与 {@link #listSourceInstitutionsByTargetTable(String)} 相同的命中规则，但在
     * 「按 institutionId 分组」之后对结果列表做 offset/limit 分页，避免一次性返回大列表。
     *
     * <p>由于聚合在内存中完成（详见类 Javadoc 的实现说明），此处的分页是结果分页；
     * 当 sync_task 表规模大幅增长后，需要改造为 PostgreSQL JSONB 查询并把分页下推到 SQL。
     *
     * @param targetTable Doris 目标表名（不区分大小写；空白参数返回空列表）
     * @param page        0-based 页号；负值会被 clamp 到 0
     * @param size        页大小；&lt;=0 时使用 {@link #DEFAULT_PAGE_SIZE}，&gt;{@link #MAX_PAGE_SIZE}
     *                    时被 clamp 到 {@link #MAX_PAGE_SIZE}
     */
    public List<InstitutionWithStatsDto> listSourceInstitutionsByTargetTable(String targetTable, int page, int size) {
        if (targetTable == null || targetTable.isBlank()) {
            return Collections.emptyList();
        }
        int safePage = Math.max(0, page);
        int safeSize = clampSize(size);
        String normalizedTarget = targetTable.trim().toLowerCase(Locale.ROOT);

        List<SyncTask> matched = syncTaskRepository.findAll().stream()
                .filter(t -> t.getInstitutionId() != null)
                .filter(t -> taskMatchesTargetTable(t, normalizedTarget))
                .toList();
        if (matched.isEmpty()) {
            return Collections.emptyList();
        }

        // 按 institutionId 分组，保留稳定顺序便于测试断言
        Map<Long, List<SyncTask>> byInst = new LinkedHashMap<>();
        for (SyncTask t : matched) {
            byInst.computeIfAbsent(t.getInstitutionId(), k -> new ArrayList<>()).add(t);
        }

        // 一次性拉取所需机构，避免 N+1
        List<Institution> institutions = institutionRepository.findAllById(byInst.keySet());
        Map<Long, Institution> instById = new HashMap<>();
        for (Institution i : institutions) {
            instById.put(i.getId(), i);
        }

        // 按机构 id 升序生成稳定输出，再做 offset/limit 切片
        List<Long> sortedInstIds = new ArrayList<>(byInst.keySet());
        Collections.sort(sortedInstIds);

        int from = Math.min((int) Math.min((long) safePage * safeSize, Integer.MAX_VALUE), sortedInstIds.size());
        int to = Math.min(from + safeSize, sortedInstIds.size());
        List<Long> pageIds = sortedInstIds.subList(from, to);

        List<InstitutionWithStatsDto> result = new ArrayList<>(pageIds.size());
        for (Long instId : pageIds) {
            Institution institution = instById.get(instId);
            List<SyncTask> tasks = byInst.get(instId);
            if (institution == null) {
                // 任务 institutionId 指向已删除机构（脏数据）：跳过并告警
                log.warn("listSourceInstitutionsByTargetTable: dangling institutionId={} on {} task(s), skipped",
                        instId, tasks.size());
                continue;
            }
            result.add(buildStatsFromTasks(institution, tasks, /* datasourceCount */ 0L));
        }
        return result;
    }

    // ── 业务查询：按目标表统计各机构实际行数（spec 069 P2）──────────────────

    /**
     * 统计贡献到指定 Doris 目标表的各机构「实际行数」（spec 069 P2）。
     *
     * <p>与 {@link #listSourceInstitutionsByTargetTable(String)}（统计口径全部来自 sync_task
     * 元数据）互补：本方法对 Doris 目标表按 {@code _etl_job_id} 实测 {@code COUNT(*)}，回答
     * 「这家机构在这张表里到底落了多少行真实数据」，是多机构共表场景的机构维度数据可观测性。
     *
     * <p>实现：
     * <ol>
     *   <li>扫 sync_task → 过滤命中 {@code targetTable} 且 {@code institutionId} 非空的任务
     *       （命中规则与 {@link #taskMatchesTargetTable} 一致，与「查贡献机构」对齐）；</li>
     *   <li>按 {@code targetDataSourceId} + 解析后的实际目标表名分组，每组对 Doris 跑一次
     *       {@code GROUP BY _etl_job_id} 的 COUNT 查询（{@code _etl_job_id = task.id}）；</li>
     *   <li>把每个 {@code _etl_job_id} 的行数累加回其任务所属机构，得到机构维度行数。</li>
     * </ol>
     *
     * <p>容错：某目标数据源连接失败 / 目标表尚未建好时，该数据源涉及的机构标注 {@code error}
     * 而非误显示 0 行；不抛断整个请求，其它数据源/机构结果照常返回。机构标识取自 institution 主表，
     * 已删除机构（脏数据 institutionId）跳过并告警。
     *
     * @param targetTable Doris 目标表名（不区分大小写；空白参数返回空列表）
     * @return 按机构 id 升序的行数统计列表；每条含 institutionId/code/name、rowCount、taskCount、error
     */
    public List<InstitutionTargetRowCountDto> countInstitutionRowsByTargetTable(String targetTable) {
        if (targetTable == null || targetTable.isBlank()) {
            return Collections.emptyList();
        }
        String normalizedTarget = targetTable.trim().toLowerCase(Locale.ROOT);

        List<SyncTask> matched = syncTaskRepository.findAll().stream()
                .filter(t -> t.getInstitutionId() != null)
                .filter(t -> t.getId() != null)
                .filter(t -> taskMatchesTargetTable(t, normalizedTarget))
                .toList();
        if (matched.isEmpty()) {
            return Collections.emptyList();
        }

        // task.id → institutionId（用于把 _etl_job_id 行数映射回机构）
        Map<Long, Long> taskToInstitution = new HashMap<>();
        // (targetDataSourceId, 实际目标表名) → 该组下的 task.id 集合
        Map<TargetTableGroup, List<Long>> groups = new LinkedHashMap<>();
        for (SyncTask t : matched) {
            taskToInstitution.put(t.getId(), t.getInstitutionId());
            Long tgtDsId = t.getTargetDataSourceId();
            String resolvedTable = resolveActualTargetTable(t, normalizedTarget);
            TargetTableGroup key = new TargetTableGroup(tgtDsId, resolvedTable);
            groups.computeIfAbsent(key, k -> new ArrayList<>()).add(t.getId());
        }

        // ── 唯一的副作用区：逐组连 Doris 跑真实 GROUP BY COUNT，产出 GroupQueryOutcome ──
        List<GroupQueryOutcome> outcomes = new ArrayList<>(groups.size());
        for (Map.Entry<TargetTableGroup, List<Long>> entry : groups.entrySet()) {
            TargetTableGroup group = entry.getKey();
            List<Long> jobIds = entry.getValue();
            TargetDataSource target = group.targetDataSourceId() == null
                    ? null
                    : targetDataSourceRepository.findById(group.targetDataSourceId()).orElse(null);
            if (target == null) {
                outcomes.add(new GroupQueryOutcome(jobIds, null,
                        "目标数据源不存在或未配置（id=" + group.targetDataSourceId() + "）"));
                continue;
            }
            try {
                Map<Long, Long> byJob = rowCountQuery.countRowsByJobId(target, group.targetTable(), jobIds);
                outcomes.add(new GroupQueryOutcome(jobIds, byJob, null));
            } catch (Exception e) {
                log.warn("countInstitutionRowsByTargetTable: query failed targetDsId={} table={}: {}",
                        group.targetDataSourceId(), group.targetTable(), e.getMessage());
                outcomes.add(new GroupQueryOutcome(jobIds, null, "查询目标表行数失败：" + e.getMessage()));
            }
        }

        // ── 纯聚合区：把各组结果映射回机构并组装 DTO（零 IO，可独立单测）──
        Set<Long> allInstitutionIds = collectInstitutionIds(taskToInstitution, outcomes);
        List<Institution> institutions = institutionRepository.findAllById(allInstitutionIds);
        Map<Long, Institution> instById = new HashMap<>();
        for (Institution i : institutions) {
            instById.put(i.getId(), i);
        }
        return assembleRowCountDtos(taskToInstitution, outcomes, instById);
    }

    /** 一组（目标库 + 目标表）的 Doris 查询结果：成功时 {@code rowsByJobId} 非空，失败时 {@code error} 非空。 */
    record GroupQueryOutcome(List<Long> jobIds, Map<Long, Long> rowsByJobId, String error) {
    }

    /** 收集所有涉及的机构 id（用于一次性 findAllById），纯函数。 */
    static Set<Long> collectInstitutionIds(Map<Long, Long> taskToInstitution,
                                           List<GroupQueryOutcome> outcomes) {
        Set<Long> ids = new java.util.LinkedHashSet<>();
        for (GroupQueryOutcome outcome : outcomes) {
            for (Long jobId : outcome.jobIds()) {
                Long instId = taskToInstitution.get(jobId);
                if (instId != null) ids.add(instId);
            }
        }
        return ids;
    }

    /**
     * 纯聚合：把各组 Doris 查询结果（{@link GroupQueryOutcome}）按 {@code _etl_job_id → institutionId}
     * 映射回机构并组装 {@link InstitutionTargetRowCountDto} 列表（按机构 id 升序）。
     *
     * <p>零 IO、零 mock，规则：
     * <ul>
     *   <li>成功组：每个 jobId 行数（缺失视为 0）累加到所属机构的 rowCount，taskCount +1；</li>
     *   <li>失败组：组内涉及机构标注首个 error（不覆盖已成功机构的统计——以「能算出多少算多少」为先，
     *       仅对完全没有成功统计的机构展示 error）；</li>
     *   <li>institution 主表查不到的脏数据 institutionId 跳过；</li>
     *   <li>同机构既有成功组又有失败组：rowCount 为成功组之和，error 留空（部分成功不报错，避免误导）。</li>
     * </ul>
     */
    static List<InstitutionTargetRowCountDto> assembleRowCountDtos(
            Map<Long, Long> taskToInstitution,
            List<GroupQueryOutcome> outcomes,
            Map<Long, Institution> instById) {

        Map<Long, long[]> instRowsAndTasks = new HashMap<>(); // institutionId → [rowCount, taskCount]
        Map<Long, String> instError = new LinkedHashMap<>();

        for (GroupQueryOutcome outcome : outcomes) {
            if (outcome.error() != null) {
                for (Long jobId : outcome.jobIds()) {
                    Long instId = taskToInstitution.get(jobId);
                    if (instId != null) instError.putIfAbsent(instId, outcome.error());
                }
                continue;
            }
            Map<Long, Long> byJob = outcome.rowsByJobId() == null ? Map.of() : outcome.rowsByJobId();
            for (Long jobId : outcome.jobIds()) {
                Long instId = taskToInstitution.get(jobId);
                if (instId == null) continue;
                long rows = byJob.getOrDefault(jobId, 0L);
                long[] agg = instRowsAndTasks.computeIfAbsent(instId, k -> new long[]{0L, 0L});
                agg[0] += rows;
                agg[1] += 1;
            }
        }

        Set<Long> allInstitutionIds = new java.util.LinkedHashSet<>();
        allInstitutionIds.addAll(instRowsAndTasks.keySet());
        allInstitutionIds.addAll(instError.keySet());
        List<Long> sortedInstIds = new ArrayList<>(allInstitutionIds);
        Collections.sort(sortedInstIds);

        List<InstitutionTargetRowCountDto> result = new ArrayList<>(sortedInstIds.size());
        for (Long instId : sortedInstIds) {
            Institution institution = instById.get(instId);
            if (institution == null) {
                continue; // 脏数据 institutionId（机构已删除）：跳过
            }
            InstitutionTargetRowCountDto dto = new InstitutionTargetRowCountDto();
            dto.setInstitutionId(instId);
            dto.setInstitutionCode(institution.getCode());
            dto.setInstitutionName(institution.getName());
            long[] agg = instRowsAndTasks.get(instId);
            if (agg != null) {
                dto.setRowCount(agg[0]);
                dto.setTaskCount(agg[1]);
                // 部分成功（既有成功又有失败组）不报错：以成功统计为准
            } else {
                dto.setRowCount(null);
                dto.setTaskCount(0L);
                dto.setError(instError.get(instId));
            }
            result.add(dto);
        }
        return result;
    }

    /**
     * 解析任务在指定（已 lower-case）目标表语义下的实际 Doris 表名。
     *
     * <p>与 {@link #taskMatchesTargetTable} 的命中规则配套：
     * <ol>
     *   <li>{@code targetTableMap} 中存在 value 等于 {@code normalizedTarget} 的映射 → 用该 value；</li>
     *   <li>否则回退到 {@code normalizedTarget} 本身（源/目标同名约定）。</li>
     * </ol>
     * 返回值始终 lower-case，与 Doris 表名小写约定一致。
     */
    private String resolveActualTargetTable(SyncTask task, String normalizedTarget) {
        String mapJson = task.getTargetTableMap();
        if (mapJson != null && !mapJson.isBlank()) {
            try {
                Map<String, String> map = OBJECT_MAPPER.readValue(mapJson, MAP_TYPE);
                for (String tgt : map.values()) {
                    if (tgt != null && tgt.toLowerCase(Locale.ROOT).equals(normalizedTarget)) {
                        return tgt.toLowerCase(Locale.ROOT);
                    }
                }
            } catch (Exception e) {
                // 解析失败回退源/目标同名
                log.debug("resolveActualTargetTable: parse targetTableMap failed for task {}: {}",
                        task.getId(), e.getMessage());
            }
        }
        return normalizedTarget;
    }

    /** 按目标库表分组的 key：相同（目标数据源, 目标表名）的任务共用一次 Doris 查询。 */
    private record TargetTableGroup(Long targetDataSourceId, String targetTable) {
    }

    // ── 业务查询：按机构查任务 / 数据源 ──────────────────────────────────────

    /**
     * 委托 {@link SyncTaskService#listByInstitution(Long, boolean)}。详见该方法 Javadoc。
     *
     * <p>spec institution-management 任务 15.2：默认对结果做 {@link #MAX_PAGE_SIZE} 硬上限截断，
     * 防止单机构关联大量任务时一次性返回过多数据。需要更细分页时改用
     * {@link #listTasksByInstitution(Long, boolean, int, int)}。
     */
    public List<SyncTaskDto> listTasksByInstitution(Long institutionId, boolean includeChildren) {
        return listTasksByInstitution(institutionId, includeChildren, 0, MAX_PAGE_SIZE);
    }

    /**
     * 分页变体：spec institution-management 任务 15.2。
     *
     * <p>下推分页 / 排序到 DB（{@link SyncTaskService#listByInstitution(Long, boolean, Pageable)}），
     * 排序固定为 {@code createdAt DESC}，与 {@link SyncTaskService#findPage} 一致。
     *
     * @param institutionId   必填
     * @param includeChildren true=含子机构（基于 parent_id 子树）
     * @param page            0-based 页号；负值会被 clamp 到 0
     * @param size            页大小；&lt;=0 时使用 {@link #DEFAULT_PAGE_SIZE}，&gt;{@link #MAX_PAGE_SIZE}
     *                        时被 clamp 到 {@link #MAX_PAGE_SIZE}
     */
    public List<SyncTaskDto> listTasksByInstitution(Long institutionId, boolean includeChildren,
                                                     int page, int size) {
        int safePage = Math.max(0, page);
        int safeSize = clampSize(size);
        Pageable pageable = PageRequest.of(safePage, safeSize, Sort.by(Sort.Direction.DESC, "createdAt"));
        return syncTaskService.listByInstitution(institutionId, includeChildren, pageable).getContent();
    }

    /**
     * 委托 {@link SourceDataSourceService#findByInstitutionId(Long)}。
     * {@code institutionId} 为空时抛 {@link IllegalArgumentException}（→ HTTP 400）。
     */
    public List<SourceDataSourceDto> listDatasourcesByInstitution(Long institutionId) {
        if (institutionId == null) {
            throw new IllegalArgumentException("institutionId 不能为空");
        }
        return sourceDataSourceService.findByInstitutionId(institutionId);
    }

    // ── 机构 + 关联资产统计 ─────────────────────────────────────────────────

    /**
     * 返回机构 + 关联资产统计，用于机构管理页列表行展示。
     *
     * <p>统计口径：
     * <ul>
     *   <li>{@code datasourceCount} = {@code source_data_source.institution_id == id} 的记录数</li>
     *   <li>{@code syncTaskCount} = {@code sync_task.institution_id == id} 的记录数</li>
     *   <li>{@code lastSyncTime} = 关联 sync_task 中 {@code last_run_time} 的最大值</li>
     *   <li>{@code statusSummary} = 关联 sync_task 按 {@code last_run_status} 分组计数；
     *       {@code last_run_status} 为空的任务记入 {@code "UNKNOWN"} 桶</li>
     * </ul>
     */
    public InstitutionWithStatsDto getInstitutionWithStats(Long institutionId) {
        if (institutionId == null) {
            throw new IllegalArgumentException("institutionId 不能为空");
        }
        Institution institution = institutionRepository.findById(institutionId)
                .orElseThrow(() -> new NoSuchElementException("Institution not found: " + institutionId));
        List<SyncTask> tasks = syncTaskRepository.findByInstitutionIdIn(Set.of(institutionId));
        long datasourceCount = sourceDataSourceRepository.countByInstitutionId(institutionId);
        return buildStatsFromTasks(institution, tasks, datasourceCount);
    }

    // ── private helpers ─────────────────────────────────────────────────────

    /**
     * 判断任务是否产出指定 Doris 目标表。
     *
     * <p>规则：
     * <ol>
     *   <li>解析 {@code targetTableMap} JSON：任意 value 与 {@code normalizedTarget} 相等（lower-case）即命中。</li>
     *   <li>若 JSON 为空或未命中：回退到 {@code viewNames}，存在与 {@code normalizedTarget}
     *       相等（lower-case）的源表名也视为命中（与 {@link TargetTableResolver}「无映射时
     *       源/目标同名」语义一致）。</li>
     * </ol>
     */
    private boolean taskMatchesTargetTable(SyncTask task, String normalizedTarget) {
        String mapJson = task.getTargetTableMap();
        if (mapJson != null && !mapJson.isBlank()) {
            try {
                Map<String, String> map = OBJECT_MAPPER.readValue(mapJson, MAP_TYPE);
                for (String tgt : map.values()) {
                    if (tgt != null && tgt.toLowerCase(Locale.ROOT).equals(normalizedTarget)) {
                        return true;
                    }
                }
            } catch (Exception e) {
                log.warn("taskMatchesTargetTable: parse targetTableMap failed for task {}: {}",
                        task.getId(), e.getMessage());
                // 解析失败时不阻塞匹配流程，继续尝试 viewNames
            }
        }
        // 回退：源/目标同名（无映射或映射未命中）
        if (task.getViewNames() != null) {
            for (String view : task.getViewNames()) {
                if (view != null && view.toLowerCase(Locale.ROOT).equals(normalizedTarget)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * 用任务列表构建机构 + 资产统计 DTO。
     *
     * @param institution    机构实体（必填）
     * @param tasks          关联任务列表（可空，用空列表表示无任务）
     * @param datasourceCount 数据源数；{@link #listSourceInstitutionsByTargetTable} 场景下传 0
     */
    private InstitutionWithStatsDto buildStatsFromTasks(Institution institution,
                                                        Collection<SyncTask> tasks,
                                                        long datasourceCount) {
        InstitutionWithStatsDto dto = new InstitutionWithStatsDto();
        dto.setInstitution(toInstitutionDto(institution));
        dto.setDatasourceCount(datasourceCount);
        dto.setSyncTaskCount(tasks == null ? 0L : tasks.size());

        LocalDateTime lastSyncTime = null;
        Map<String, Long> statusSummary = new LinkedHashMap<>();
        if (tasks != null) {
            for (SyncTask t : tasks) {
                if (t.getLastRunTime() != null
                        && (lastSyncTime == null || t.getLastRunTime().isAfter(lastSyncTime))) {
                    lastSyncTime = t.getLastRunTime();
                }
                String status = t.getLastRunStatus();
                if (status == null || status.isBlank()) status = "UNKNOWN";
                statusSummary.merge(status, 1L, Long::sum);
            }
        }
        dto.setLastSyncTime(lastSyncTime);
        dto.setStatusSummary(statusSummary);
        return dto;
    }

    private InstitutionDto toInstitutionDto(Institution e) {
        InstitutionDto dto = new InstitutionDto();
        dto.setId(e.getId());
        dto.setCode(e.getCode());
        dto.setName(e.getName());
        dto.setShortName(e.getShortName());
        dto.setType(e.getType());
        dto.setLevel(e.getLevel());
        dto.setRegionCode(e.getRegionCode());
        dto.setParentId(e.getParentId());
        dto.setEnabled(e.getEnabled());
        dto.setDescription(e.getDescription());
        dto.setCreatedAt(e.getCreatedAt());
        dto.setUpdatedAt(e.getUpdatedAt());
        return dto;
    }

    /**
     * 统一的页大小 clamp：&lt;=0 用 {@link #DEFAULT_PAGE_SIZE}，&gt;{@link #MAX_PAGE_SIZE}
     * 截断到 {@link #MAX_PAGE_SIZE}。
     */
    private static int clampSize(int size) {
        if (size <= 0) {
            return DEFAULT_PAGE_SIZE;
        }
        return Math.min(size, MAX_PAGE_SIZE);
    }
}
