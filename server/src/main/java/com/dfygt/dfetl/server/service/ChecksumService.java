package com.dfygt.dfetl.server.service;

import com.dfygt.dfetl.server.common.AesUtil;
import com.dfygt.dfetl.server.engine.checksum.ChecksumProperties;
import com.dfygt.dfetl.server.engine.checksum.ChunkPlanner;
import com.dfygt.dfetl.server.engine.checksum.HashCodec;
import com.dfygt.dfetl.server.engine.checksum.RowNormalizer;
import com.dfygt.dfetl.server.entity.EtlVerifyChunk;
import com.dfygt.dfetl.server.entity.EtlVerifyDiff;
import com.dfygt.dfetl.server.entity.SourceDataSource;
import com.dfygt.dfetl.server.entity.SyncTask;
import com.dfygt.dfetl.server.entity.TargetDataSource;
import com.dfygt.dfetl.server.medical.MedicalDatasetContract;
import com.dfygt.dfetl.server.medical.MedicalContractSnapshotCodec;
import com.dfygt.dfetl.server.medical.MedicalDatasetContractService;
import com.dfygt.dfetl.server.medical.MedicalFieldContract;
import com.dfygt.dfetl.server.medical.source.MedicalSourceSelectBuilder;
import com.dfygt.dfetl.server.medical.source.MedicalSourceSelectPlan;
import com.dfygt.dfetl.server.medical.source.SourceDialectAdapter;
import com.dfygt.dfetl.server.medical.source.SourceDialectAdapterResolver;
import com.dfygt.dfetl.server.repository.EtlVerifyChunkRepository;
import com.dfygt.dfetl.server.repository.EtlVerifyDiffRepository;
import com.dfygt.dfetl.server.repository.SourceDataSourceRepository;
import com.dfygt.dfetl.server.repository.SyncTaskRepository;
import com.dfygt.dfetl.server.repository.TargetDataSourceRepository;
import com.dfygt.dfetl.server.repository.TaskExecutionRepository;
import com.dfygt.dfetl.server.service.validation.ValidationWhereBuilder;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigInteger;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

/**
 * Spec 023：Checksum 执行引擎主入口。
 *
 * <p>v1 范围：
 * <ul>
 *   <li>单列数值主键，使用 {@code upsertKeys[0]}（也兼容 {@code splitPk}）</li>
 *   <li>Java 回退路径：JDBC 分页拉取 + {@link RowNormalizer} + {@link HashCodec}</li>
 *   <li>双端按主键范围分片，逐分片对比；不一致分片下钻产出 diff</li>
 * </ul>
 *
 * <p>不在 v1 范围：服务端 SQL hash、复合主键、并发分片、diff 的修复（spec 024）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChecksumService {

    /**
     * spec validation-workbench-redesign Phase 3：全局并发限流。
     *
     * <p>限制同时执行 verify() 的任务数，防止多个 CHECKSUM 任务并发把 PG 连接池打满。
     * 默认最大并发 3（HikariCP maximum-pool-size=20，每次 verify 占 2 连接 src+tgt，
     * 3 并发 × 2 = 6 连接，留 14 给其他业务）。
     *
     * <p>超过并发上限的 verify 调用会阻塞等待（最多 5 分钟），超时抛 RuntimeException。
     */
    private static final Semaphore GLOBAL_VERIFY_LIMITER = new Semaphore(3, true);
    private static final long GLOBAL_VERIFY_TIMEOUT_MS = 300_000L;

    private final SyncTaskRepository syncTaskRepo;
    private final TaskExecutionRepository executionRepo;
    private final TargetDataSourceRepository targetDsRepo;
    private final SourceDataSourceService sourceDataSourceService;
    private final SourceDataSourceRepository sourceDsRepo;
    private final EtlVerifyChunkRepository chunkRepo;
    private final EtlVerifyDiffRepository diffRepo;
    private final WhereClauseBuilder whereClauseBuilder;
    private final ChecksumProperties props;
    private final AesUtil aesUtil;
    private final com.dfygt.dfetl.server.common.JdbcConnectionPoolManager connectionPoolManager;
    private final ValidationWhereBuilder validationWhereBuilder;
    private final DiffFieldPrecomputeService diffFieldPrecomputeService;
    private final TargetTableResolver targetTableResolver;
    private final TargetFieldResolver targetFieldResolver;
    private final ValidationRunService validationRunService;
    private final SourceTableResolver sourceTableResolver;
    private final DialectQuoteHelper dialectQuoteHelper;
    private final MedicalDatasetContractService medicalContractService;
    private final MedicalSourceSelectBuilder medicalSourceSelectBuilder;
    private final SourceDialectAdapterResolver sourceDialectAdapterResolver;

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

    public record VerifyReport(
            Long taskId,
            Long execId,
            Long runId,
            int totalChunks,
            int matchedChunks,
            int diffChunks,
            long sourceCount,
            long targetCount,
            long diffCount,
            int resumedChunks,
            String message
    ) {}

    /**
     * Spec 048：时间窗口过滤器。
     * active() == true 时在源端/目标端 SQL 中追加 {@code field >= start AND field < end}。
     */
    record WindowFilter(String field, Instant start, Instant end) {
        static final WindowFilter NONE = new WindowFilter(null, null, null);

        boolean active() {
            return field != null && !field.isBlank() && (start != null || end != null);
        }

        /** 追加 window WHERE 子句到 StringBuilder（假设前面已有 WHERE 或待拼 AND）。 */
        void appendWhere(StringBuilder sb, boolean alreadyHasWhere) {
            if (!active()) return;
            sb.append(alreadyHasWhere ? " AND " : " WHERE ");
            if (start != null) {
                sb.append(field).append(" >= ?");
            }
            if (end != null) {
                if (start != null) {
                    sb.append(" AND ");
                }
                sb.append(field).append(" < ?");
            }
        }

        /** 绑定 window 参数到 PreparedStatement，返回下一个参数索引。 */
        int bindParams(PreparedStatement ps, int startIdx) throws java.sql.SQLException {
            if (!active()) return startIdx;
            if (start != null) {
                ps.setTimestamp(startIdx++, Timestamp.from(start));
            }
            if (end != null) {
                ps.setTimestamp(startIdx++, Timestamp.from(end));
            }
            return startIdx;
        }
    }

    record ChecksumSourcePlan(
            String fromSql,
            List<String> pkCols,
            String firstPk,
            List<String> columns,
            String whereClause
    ) {}

    private record MedicalChecksumOptions(
            String datasetCode,
            String compatibilityMode,
            Map<String, String> fieldMapping,
            String validSourceQuery
    ) {}

    /**
     * 触发一次 Checksum。{@code execId} 仅作为 verify 关联键，无需指向真实 task_execution。
     */
    public VerifyReport verify(Long taskId, Long execId) {
        return verify(taskId, execId, null);
    }

    /**
     * Spec 032：断点续跑。
     */
    public VerifyReport verify(Long taskId, Long execId, Long resumeFromExecId) {
        return verify(taskId, execId, resumeFromExecId, (Instant) null, null);
    }

    /**
     * Spec 048：带时间窗口的 Checksum。
     * {@code windowStart/windowEnd} 非 null 时，源端/目标端 SQL 额外追加
     * {@code incrementalField >= windowStart AND incrementalField < windowEnd}，
     * 只验证本次增量窗口内的数据。
     */
    public VerifyReport verify(Long taskId, Long execId, Long resumeFromExecId,
                               Instant windowStart, Instant windowEnd) {
        return verify(taskId, execId, resumeFromExecId, windowStart, windowEnd, null);
    }

    /**
     * Spec 063：带任务级 checksum 算法配置的 Checksum。
     */
    public VerifyReport verify(Long taskId, Long execId, Long resumeFromExecId,
                               Instant windowStart, Instant windowEnd,
                               String checksumAlgo) {
        WatermarkService.WindowContext window = windowStart == null && windowEnd == null
                ? null
                : new WatermarkService.WindowContext("INCREMENT", windowStart, windowEnd, null, null);
        return verify(taskId, execId, resumeFromExecId, window, checksumAlgo);
    }

    /**
     * ID_RANGE 校验入口：调用方必须传入完整 WindowContext，避免退化为全量 Checksum。
     *
     * <p>spec validation-workbench-redesign Phase 3：全局并发限流。
     * 同时执行的 verify 不超过 3 个，超出阻塞等待（最多 5 分钟）。
     */
    public VerifyReport verify(Long taskId, Long execId, Long resumeFromExecId,
                               WatermarkService.WindowContext window,
                               String checksumAlgo) {
        boolean acquired = false;
        try {
            acquired = GLOBAL_VERIFY_LIMITER.tryAcquire(GLOBAL_VERIFY_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Checksum verify 被中断（等待全局并发许可时）", ie);
        }
        if (!acquired) {
            throw new RuntimeException("Checksum verify 超时：全局并发已满（最大 3），等待 5 分钟仍未获得许可。请稍后重试。");
        }
        try {
            return doVerify(taskId, execId, resumeFromExecId, window, checksumAlgo);
        } finally {
            GLOBAL_VERIFY_LIMITER.release();
        }
    }

    /** 实际 verify 逻辑（由 verify 入口持有全局信号量后调用）。 */
    private VerifyReport doVerify(Long taskId, Long execId, Long resumeFromExecId,
                                  WatermarkService.WindowContext window,
                                  String checksumAlgo) {
        SyncTask task = syncTaskRepo.findById(taskId)
                .orElseThrow(() -> new NoSuchElementException("SyncTask not found: " + taskId));
        if (isCustomSql(task)) {
            throw new UnsupportedOperationException("CUSTOM_SQL 模式暂不支持 CHECKSUM 校验");
        }
        WatermarkService.WindowContext effectiveWindow = normalizeWindow(task, window);
        if (execId == null) {
            execId = ValidationRunService.nextSyntheticLegacyExecId();
        }
        applyExecutionMedicalValidSourceQuery(execId, task);
        String scope = isWindowScoped(effectiveWindow) ? "WINDOW" : "FULL";
        Long validationRunId = validationRunService
                .getOrCreate(taskId, execId, "CHECKSUM", scope,
                        effectiveWindow.windowStart(), effectiveWindow.windowEnd())
                .getId();
        // 重新运行时只清除未修复的旧差异记录，保留已修复的历史
        chunkRepo.deleteByValidationRunId(validationRunId);
        diffRepo.deleteByValidationRunIdAndRepairStatus(validationRunId, "PENDING");
        // 1. 主键列（spec 027：支持复合主键，chunk 仅按首列）
        List<String> pkCols = resolvePkCols(task);
        String firstPk = pkCols.get(0);
        // 2. 表名/schema
        if (task.getViewNames() == null || task.getViewNames().isEmpty()) {
            throw new IllegalArgumentException("任务未配置 viewNames");
        }
        if (task.getViewNames().size() != 1) {
            throw new IllegalArgumentException("Checksum v1 仅支持单表/单视图任务");
        }
        String configuredSrcTable = task.getViewNames().get(0);
        SourceDataSource srcDs = sourceDsRepo.findById(task.getSourceDataSourceId())
                .orElseThrow(() -> new NoSuchElementException("SourceDataSource not found"));
        SourceTableResolver.SourceRelation sourceRelation =
                sourceTableResolver.resolveRequired(task, srcDs, configuredSrcTable);
        String srcDialect = sourceRelation.dialect();
        String srcSchema = sourceRelation.schema();
        String srcTable = sourceRelation.table();
        TargetDataSource tgt = targetDsRepo.findById(task.getTargetDataSourceId())
                .orElseThrow(() -> new NoSuchElementException("TargetDataSource not found"));
        // 按 targetTableMap 解析目标表名；无映射则退回 srcTable 小写
        String tgtTable = resolveTargetTable(task, srcTable);

        // 3. 列清单：以源端列为基准
        List<SourceDataSourceService.ColumnInfo> sourceColumnInfos = sourceDataSourceService
                .listColumns(task.getSourceDataSourceId(), srcSchema, srcTable);
        List<String> srcCols = sourceColumnInfos.stream().map(c -> c.columnName()).toList();
        if (srcCols.isEmpty()) {
            throw new IllegalStateException("源表无可用列");
        }
        // 校验所有列名安全
        for (String c : srcCols) {
            if (!whereClauseBuilder.isFieldNameSafe(c)) {
                throw new IllegalArgumentException("非法列名: " + c);
            }
        }
        String sourceWhere = buildEffectiveSourceWhere(
                task,
                srcDialect,
                srcTable,
                buildSourceWhere(task, srcDialect, srcTable, effectiveWindow),
                srcCols);
        ChecksumSourcePlan sourcePlan = buildChecksumSourcePlan(
                task,
                sourceRelation,
                sourceColumnInfos,
                pkCols,
                sourceWhere);

        String algo = checksumAlgo != null && !checksumAlgo.isBlank() ? checksumAlgo : props.defaultAlgo();
        HashCodec codec = new HashCodec(algo);
        RowNormalizer normalizer = new RowNormalizer();

        // spec 048：构建 WindowFilter
        WindowFilter targetWindow = buildTargetTimeWindowFilter(task, srcTable, effectiveWindow);
        String targetIdRangeWhere = buildTargetIdRangeWhere(task, srcTable, effectiveWindow);
        // spec 063：Doris MERGE 软删除时，目标侧排除 __doris_delete_sign__ = 1 的已删除行
        targetIdRangeWhere = ValidationWhereBuilder.appendDorisDeleteSignFilter(task, targetIdRangeWhere);
        // spec 069：多机构共表目标侧任务范围过滤（_etl_job_id = taskId）。
        // targetWhere 流入目标端所有分片查询（count / minmax / readChunkHashes），单点注入即全覆盖。
        targetIdRangeWhere = validationWhereBuilder.appendTenantScopeFilter(task, targetIdRangeWhere);
        final WindowFilter targetWindowFilter = targetWindow;
        final String targetWhere = targetIdRangeWhere;
        List<String> targetPkCols = resolveTargetChecksumColumns(task, srcTable, sourcePlan.pkCols());
        String targetFirstPk = resolveTargetChecksumColumn(task, srcTable, sourcePlan.firstPk());
        List<String> targetCols = resolveTargetChecksumColumns(task, srcTable, sourcePlan.columns());

        // 4. 计算分片：使用源端有效行与目标端当前行的 union PK range，避免漏检目标端额外行。
        long sourceRows;
        BigInteger sourceMinPk, sourceMaxPk;
        try (Connection src = sourceDataSourceService.openConnection(task.getSourceDataSourceId())) {
            sourceRows = countRowsFrom(src, srcDialect, sourcePlan.fromSql(), null, null,
                    sourcePlan.firstPk(), sourcePlan.whereClause(), WindowFilter.NONE);
            BigInteger[] mm = minMaxPkFrom(src, srcDialect, sourcePlan.fromSql(),
                    sourcePlan.firstPk(), sourcePlan.whereClause(), WindowFilter.NONE);
            sourceMinPk = mm[0];
            sourceMaxPk = mm[1];
        } catch (Exception e) {
            throw new RuntimeException("获取源端分片范围失败: " + e.getMessage(), e);
        }
        long targetRows;
        BigInteger targetMinPk, targetMaxPk;
        try (Connection tgtConn = openDorisConn(tgt)) {
            targetRows = countRows(tgtConn, "DORIS", tgt.getDbName(), tgtTable, null, null,
                    targetFirstPk, targetWhere, targetWindowFilter);
            BigInteger[] mm = minMaxPk(tgtConn, "DORIS", tgt.getDbName(), tgtTable,
                    targetFirstPk, targetWhere, targetWindowFilter);
            targetMinPk = mm[0];
            targetMaxPk = mm[1];
        } catch (Exception e) {
            throw new RuntimeException("获取目标端分片范围失败: " + e.getMessage(), e);
        }
        BigInteger[] unionRange = unionPkRange(sourceMinPk, sourceMaxPk, targetMinPk, targetMaxPk);
        long totalRows = Math.max(sourceRows, targetRows);

        // spec code-review-hardening 7.1：非数值主键前置拦截
        if (unionRange[0] == null && unionRange[1] == null) {
            long nonNumericRowLimit = 5_000_000L;
            if (totalRows > nonNumericRowLimit) {
                throw new RuntimeException(
                        "CHECKSUM 校验不支持超过 500 万行的非数值主键表，请改用 ROW_COUNT 校验"
                                + "（当前行数: " + totalRows + "）");
            }
            log.warn("非数值主键，退化为单分片全量扫描，行数={}", totalRows);
        }

        List<ChunkPlanner.Chunk> chunks = new ChunkPlanner()
                .plan(unionRange[0], unionRange[1], totalRows, props.chunkSizeRows());

        // 5a. spec 032：断点续跑预读
        Set<Integer> skipChunkNos = java.util.Collections.emptySet();
        Map<Integer, EtlVerifyChunk> resumedSnapshots = java.util.Collections.emptyMap();
        if (resumeFromExecId != null) {
            List<EtlVerifyChunk> matchedRows = chunkRepo.findByTaskIdAndExecIdAndMatchedTrue(taskId, resumeFromExecId);
            if (matchedRows.isEmpty()) {
                log.warn("Checksum.resume execId={} 无 matched 分片，将全量重跑", resumeFromExecId);
            } else {
                skipChunkNos = matchedRows.stream().map(EtlVerifyChunk::getChunkNo).collect(java.util.stream.Collectors.toSet());
                resumedSnapshots = matchedRows.stream().collect(java.util.stream.Collectors.toMap(EtlVerifyChunk::getChunkNo, r -> r, (a, b) -> a));
            }
        }
        final Set<Integer> skipSet = skipChunkNos;

        log.info("Checksum.verify taskId={} execId={} pkCols={} cols={} totalRows={} chunks={} parallelism={} resumeFrom={} skipped={} algo={} window={}",
                taskId, execId, sourcePlan.pkCols(), sourcePlan.columns().size(), totalRows, chunks.size(), props.parallelism(),
                resumeFromExecId, skipSet.size(), codec.algo(),
                !sourceWhere.isBlank()
                        ? sourceWhere
                        : "FULL");

        // 5. spec 028：分片处理（并发或串行）
        AtomicInteger matchedCount = new AtomicInteger(0);
        AtomicInteger diffChunkCount = new AtomicInteger(0);
        AtomicLong srcSum = new AtomicLong(0);
        AtomicLong tgtSum = new AtomicLong(0);
        AtomicLong diffSum = new AtomicLong(0);
        AtomicInteger resumedCount = new AtomicInteger(0);
        ConcurrentLinkedQueue<EtlVerifyChunk> chunkRows = new ConcurrentLinkedQueue<>();
        long verifyExec = execId;

        // 5b. 写入 resumed 分片快照（不计入 matched，单独统计）
        for (Integer skipNo : skipSet) {
            EtlVerifyChunk prev = resumedSnapshots.get(skipNo);
            EtlVerifyChunk row = new EtlVerifyChunk();
            row.setTaskId(taskId);
            row.setExecId(verifyExec);
            row.setValidationRunId(validationRunId);
            row.setChunkNo(skipNo);
            row.setChunkStart(prev.getChunkStart());
            row.setChunkEnd(prev.getChunkEnd());
            row.setSourceCount(prev.getSourceCount());
            row.setTargetCount(prev.getTargetCount());
            row.setSourceChecksum(prev.getSourceChecksum());
            row.setTargetChecksum(prev.getTargetChecksum());
            row.setMatched(true);
            row.setFinishedAt(OffsetDateTime.now());
            chunkRows.add(row);
            resumedCount.incrementAndGet();
            if (prev.getSourceCount() != null) srcSum.addAndGet(prev.getSourceCount());
            if (prev.getTargetCount() != null) tgtSum.addAndGet(prev.getTargetCount());
        }

        int parallelism = props.parallelism() == null || props.parallelism() <= 1 ? 1 : props.parallelism();

        if (parallelism <= 1) {
            // 串行兜底：保留与 spec 023 完全一致的行为，便于排障
            try (Connection srcConn = sourceDataSourceService.openConnection(task.getSourceDataSourceId());
                 Connection tgtConn = openDorisConn(tgt)) {
                for (ChunkPlanner.Chunk c : chunks) {
                    if (skipSet.contains(c.chunkNo())) continue;
                    processChunk(c, srcConn, tgtConn, srcDialect, sourcePlan.fromSql(), "DORIS", tgt.getDbName(), tgtTable,
                            sourcePlan.pkCols(), sourcePlan.firstPk(), sourcePlan.columns(), targetPkCols, targetFirstPk, targetCols, normalizer, codec,
                            taskId, verifyExec, chunkRows, validationRunId,
                            matchedCount, diffChunkCount, srcSum, tgtSum, diffSum, sourcePlan.whereClause(), targetWhere, targetWindowFilter);
                }
            } catch (Exception e) {
                throw new RuntimeException("Checksum 执行失败: " + e.getMessage(), e);
            }
        } else {
            // 并发：每分片任务独立打开 src/tgt 连接（JDBC 非线程安全）
            ExecutorService exec = Executors.newVirtualThreadPerTaskExecutor();
            Semaphore limiter = new Semaphore(parallelism);
            List<Future<?>> futures = new ArrayList<>(chunks.size());
            try {
                for (ChunkPlanner.Chunk c : chunks) {
                    if (skipSet.contains(c.chunkNo())) continue;
                    limiter.acquire();
                    futures.add(exec.submit(() -> {
                        try (Connection srcConn = sourceDataSourceService.openConnection(task.getSourceDataSourceId());
                             Connection tgtConn = openDorisConn(tgt)) {
                            processChunk(c, srcConn, tgtConn, srcDialect, sourcePlan.fromSql(), "DORIS", tgt.getDbName(), tgtTable,
                                    sourcePlan.pkCols(), sourcePlan.firstPk(), sourcePlan.columns(), targetPkCols, targetFirstPk, targetCols, normalizer, codec,
                                    taskId, verifyExec, chunkRows, validationRunId,
                                    matchedCount, diffChunkCount, srcSum, tgtSum, diffSum, sourcePlan.whereClause(), targetWhere, targetWindowFilter);
                            return null;
                        } finally {
                            limiter.release();
                        }
                    }));
                }
                // 等待所有完成；任何分片异常都向上抛
                for (Future<?> f : futures) {
                    f.get();
                }
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                throw new RuntimeException("Checksum 被中断", ie);
            } catch (ExecutionException ee) {
                // fail-fast：取消未完成
                futures.forEach(f -> f.cancel(true));
                Throwable cause = ee.getCause() != null ? ee.getCause() : ee;
                throw new RuntimeException("Checksum 分片执行失败: " + cause.getMessage(), cause);
            } finally {
                exec.shutdown();
                try {
                    if (!exec.awaitTermination(5, TimeUnit.SECONDS)) exec.shutdownNow();
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    exec.shutdownNow();
                }
            }
        }

        // 6. 批量落 chunk 表（顺序按 chunkNo 即可还原）
        if (!chunkRows.isEmpty()) {
            chunkRepo.saveAll(chunkRows);
        }

        // spec 056：diffCount > 0 时异步预计算字段级差异（不阻塞 verify 返回）
        if (diffSum.get() > 0) {
            diffFieldPrecomputeService.precomputeAsync(taskId, verifyExec);
        }

        return new VerifyReport(taskId, verifyExec, validationRunId, chunks.size(), matchedCount.get(), diffChunkCount.get(),
                srcSum.get(), tgtSum.get(), diffSum.get(), resumedCount.get(),
                resumeFromExecId == null ? "OK" : ("OK (resumed " + resumedCount.get() + " chunks from execId=" + resumeFromExecId + ")"));
    }

    /** 处理单个分片：双端读 hash、聚合、对比、不一致下钻。线程安全（仅写入并发结构）。 */
    private void processChunk(ChunkPlanner.Chunk c,
                              Connection srcConn, Connection tgtConn,
                              String srcDialect, String srcFromSql,
                              String tgtDialect, String tgtSchema, String tgtTable,
                              List<String> pkCols, String firstPk, List<String> srcCols,
                              List<String> tgtPkCols, String tgtFirstPk, List<String> tgtCols,
                              RowNormalizer normalizer, HashCodec codec,
                              Long taskId, Long verifyExec,
                              ConcurrentLinkedQueue<EtlVerifyChunk> chunkRows, Long validationRunId,
                              AtomicInteger matchedCount, AtomicInteger diffChunkCount,
                              AtomicLong srcSum, AtomicLong tgtSum, AtomicLong diffSum,
                              String sourceWhere, String targetWhere, WindowFilter targetWindow) throws Exception {
        Map<String, String> srcMap = readChunkHashesFrom(srcConn, srcDialect, srcFromSql,
                pkCols, firstPk, srcCols, c, normalizer, codec, sourceWhere, WindowFilter.NONE);
        Map<String, String> tgtMap = readChunkHashes(tgtConn, tgtDialect, tgtSchema, tgtTable,
                tgtPkCols, tgtFirstPk, tgtCols, c, normalizer, codec, targetWhere, targetWindow);

        String srcChecksum = codec.aggregate(srcMap.values());
        String tgtChecksum = codec.aggregate(tgtMap.values());
        boolean matched = chunkRowsMatch(srcMap, tgtMap);

        EtlVerifyChunk row = new EtlVerifyChunk();
        row.setTaskId(taskId);
        row.setExecId(verifyExec);
        row.setValidationRunId(validationRunId);
        row.setChunkNo(c.chunkNo());
        row.setChunkStart(c.startStr());
        row.setChunkEnd(c.endStr());
        row.setSourceCount((long) srcMap.size());
        row.setTargetCount((long) tgtMap.size());
        row.setSourceChecksum(srcChecksum);
        row.setTargetChecksum(tgtChecksum);
        row.setMatched(matched);
        row.setFinishedAt(OffsetDateTime.now());
        // spec 048：记录窗口范围
        if (targetWindow.active()) {
            if (targetWindow.start() != null) {
                row.setScopedWindowStart(OffsetDateTime.ofInstant(targetWindow.start(), java.time.ZoneOffset.UTC));
            }
            if (targetWindow.end() != null) {
                row.setScopedWindowEnd(OffsetDateTime.ofInstant(targetWindow.end(), java.time.ZoneOffset.UTC));
            }
        }
        chunkRows.add(row);

        srcSum.addAndGet(srcMap.size());
        tgtSum.addAndGet(tgtMap.size());
        if (matched) {
            matchedCount.incrementAndGet();
        } else {
            diffChunkCount.incrementAndGet();
            // drillDown 内部用 diffRepo.saveAll，JpaRepository 默认线程安全
            diffSum.addAndGet(drillDown(taskId, verifyExec, validationRunId, c.chunkNo(), srcMap, tgtMap, pkCols));
        }
    }

    // ── 读取一个分片的 (pkValue → rowHash) 映射 ───────────────────────────────
    // spec 027：pkCols 全部参与对齐 key 拼接（\u001e 分隔），firstPk 用于 chunk 范围过滤
    // spec 048：window 非空时叠加时间范围过滤
    private Map<String, String> readChunkHashes(Connection conn, String dialect, String schema, String table,
                                                List<String> pkCols, String firstPk, List<String> cols,
                                                ChunkPlanner.Chunk c,
                                                RowNormalizer normalizer, HashCodec codec,
                                                WindowFilter window) throws Exception {
        return readChunkHashes(conn, dialect, schema, table, pkCols, firstPk, cols, c, normalizer, codec, "", window);
    }

    private Map<String, String> readChunkHashes(Connection conn, String dialect, String schema, String table,
                                                List<String> pkCols, String firstPk, List<String> cols,
                                                ChunkPlanner.Chunk c,
                                                RowNormalizer normalizer, HashCodec codec,
                                                String whereClause,
                                                WindowFilter window) throws Exception {
        DialectQuoteHelper quoteHelper = new DialectQuoteHelper();
        return readChunkHashesFrom(
                conn,
                dialect,
                quoteHelper.qualifyTable(dialect, schema, table),
                pkCols,
                firstPk,
                cols,
                c,
                normalizer,
                codec,
                whereClause,
                window);
    }

    private Map<String, String> readChunkHashesFrom(Connection conn, String dialect, String fromSql,
                                                    List<String> pkCols, String firstPk, List<String> cols,
                                                    ChunkPlanner.Chunk c,
                                                    RowNormalizer normalizer, HashCodec codec,
                                                    String whereClause,
                                                    WindowFilter window) throws Exception {
        Map<String, String> map = new HashMap<>(Math.max(16, props.chunkPageSize()));
        String sql = buildReadChunkSqlFrom(dialect, fromSql, pkCols, firstPk, cols, c, whereClause, window);
        boolean ranged = c.start() != null && c.end() != null;
        int pkCount = pkCols.size();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            int idx = 1;
            if (ranged) {
                ps.setString(idx++, c.startStr());
                ps.setString(idx++, c.endStr());
            }
            window.bindParams(ps, idx);
            ps.setFetchSize(props.chunkPageSize());
            try (ResultSet rs = ps.executeQuery()) {
                ResultSetMetaData md = rs.getMetaData();
                int colCount = md.getColumnCount();
                while (rs.next()) {
                    StringBuilder keyBuf = new StringBuilder();
                    boolean nullPk = false;
                    for (int i = 1; i <= pkCount; i++) {
                        Object v = rs.getObject(i);
                        if (v == null) { nullPk = true; break; }
                        if (i > 1) keyBuf.append('\u001e');
                        keyBuf.append(normalizer.normalize(v));
                    }
                    if (nullPk) continue;
                    String pkKey = keyBuf.toString();
                    List<Object> vals = new ArrayList<>(colCount - pkCount);
                    for (int i = pkCount + 1; i <= colCount; i++) {
                        vals.add(rs.getObject(i));
                    }
                    String rowHash = codec.hash(normalizer.normalizeRow(vals));
                    map.put(pkKey, rowHash);
                }
            }
        }
        return map;
    }

    // ── 不一致分片下钻 ────────────────────────────────────────────────────
    private long drillDown(Long taskId, Long execId, Long validationRunId, int chunkNo,
                           Map<String, String> srcMap, Map<String, String> tgtMap,
                           List<String> pkCols) {
        long count = 0;
        int max = props.drillDownMaxRows();
        Set<String> allKeys = new HashSet<>(srcMap.keySet());
        allKeys.addAll(tgtMap.keySet());

        List<EtlVerifyDiff> buffer = new ArrayList<>();
        for (String pk : allKeys) {
            if (count >= max) {
                log.warn("Checksum drillDown taskId={} chunk={} 达到上限 {}，剩余差异未记录", taskId, chunkNo, max);
                break;
            }
            String sh = srcMap.get(pk);
            String th = tgtMap.get(pk);
            String type;
            if (sh != null && th == null) type = EtlVerifyDiff.TYPE_INSERT_MISSING;
            else if (sh == null && th != null) type = EtlVerifyDiff.TYPE_DELETE_MISSING;
            else if (sh != null && !sh.equals(th)) type = EtlVerifyDiff.TYPE_UPDATE_DIFF;
            else continue;

            EtlVerifyDiff d = new EtlVerifyDiff();
            d.setTaskId(taskId);
            d.setExecId(execId);
            d.setValidationRunId(validationRunId);
            d.setChunkNo(chunkNo);
            d.setPkValue(PkValueCodec.encodeInternalKey(pk, pkCols.size()));
            d.setDiffType(type);
            d.setSourceHash(sh);
            d.setTargetHash(th);
            buffer.add(d);
            count++;
            if (buffer.size() >= 500) {
                diffRepo.saveAll(buffer);
                buffer.clear();
            }
        }
        if (!buffer.isEmpty()) diffRepo.saveAll(buffer);
        return count;
    }

    // ── 工具方法 ──────────────────────────────────────────────────────────
    /** spec 048：支持可选 WindowFilter 的行数统计。 */
    private long countRows(Connection conn, String dialect, String schema, String table,
                           BigInteger start, BigInteger end, String pkCol,
                           WindowFilter window) throws Exception {
        return countRows(conn, dialect, schema, table, start, end, pkCol, "", window);
    }

    private long countRows(Connection conn, String dialect, String schema, String table,
                           BigInteger start, BigInteger end, String pkCol,
                           String whereClause, WindowFilter window) throws Exception {
        DialectQuoteHelper quoteHelper = new DialectQuoteHelper();
        return countRowsFrom(
                conn,
                dialect,
                quoteHelper.qualifyTable(dialect, schema, table),
                start,
                end,
                pkCol,
                whereClause,
                window);
    }

    private long countRowsFrom(Connection conn, String dialect, String fromSql,
                               BigInteger start, BigInteger end, String pkCol,
                               String whereClause, WindowFilter window) throws Exception {
        try (PreparedStatement ps = conn.prepareStatement(
                buildCountRowsSqlFrom(dialect, fromSql, start, end, pkCol, whereClause, window))) {
            int idx = 1;
            if (start != null && end != null) {
                ps.setString(idx++, start.toString());
                ps.setString(idx++, end.toString());
            }
            window.bindParams(ps, idx);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : 0L;
            }
        }
    }

    /** spec 048：支持可选 WindowFilter 的 min/max 主键查询。 */
    private BigInteger[] minMaxPk(Connection conn, String dialect, String schema, String table,
                                  String pkCol, WindowFilter window) throws Exception {
        return minMaxPk(conn, dialect, schema, table, pkCol, "", window);
    }

    private BigInteger[] minMaxPk(Connection conn, String dialect, String schema, String table,
                                  String pkCol, String whereClause, WindowFilter window) throws Exception {
        DialectQuoteHelper quoteHelper = new DialectQuoteHelper();
        return minMaxPkFrom(
                conn,
                dialect,
                quoteHelper.qualifyTable(dialect, schema, table),
                pkCol,
                whereClause,
                window);
    }

    private BigInteger[] minMaxPkFrom(Connection conn, String dialect, String fromSql,
                                      String pkCol, String whereClause, WindowFilter window) throws Exception {
        try (PreparedStatement ps = conn.prepareStatement(
                buildMinMaxPkSqlFrom(dialect, fromSql, pkCol, whereClause, window))) {
            window.bindParams(ps, 1);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return new BigInteger[]{null, null};
                String min = rs.getString(1);
                String max = rs.getString(2);
                return new BigInteger[]{
                        parseBigIntOrNull(min),
                        parseBigIntOrNull(max)
                };
            }
        }
    }

    private static BigInteger parseBigIntOrNull(String s) {
        if (s == null) return null;
        try { return new BigInteger(s.trim()); }
        catch (NumberFormatException e) { return null; }
    }

    private Connection openDorisConn(TargetDataSource tgt) throws Exception {
        String url = "jdbc:mysql://" + tgt.getFeHost() + ":" + tgt.getFePort() + "/" + tgt.getDbName()
                + "?useSSL=false&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true";
        return connectionPoolManager.getConnection(url, tgt.getUsername(), aesUtil.decrypt(tgt.getPasswordEnc()));
    }

    /** 返回校验行身份列。优先使用真实业务键 upsertKeys，splitPk 仅作无业务键时的兼容 fallback。 */
    private List<String> resolvePkCols(SyncTask task) {
        List<String> keys = task.getUpsertKeys();
        if (keys != null && !keys.isEmpty()) {
            for (String col : keys) {
                if (!whereClauseBuilder.isFieldNameSafe(col)) {
                    throw new IllegalArgumentException("非法 upsertKeys 列名: " + col);
                }
            }
            return List.copyOf(keys);
        }
        String splitPk = task.getSplitPk();
        if (splitPk == null || splitPk.isBlank()) {
            throw new IllegalArgumentException("Checksum 需要 upsertKeys 或 splitPk 配置主键列");
        }
        if (!whereClauseBuilder.isFieldNameSafe(splitPk)) {
            throw new IllegalArgumentException("非法 splitPk: " + splitPk);
        }
        return List.of(splitPk);
    }

    // ── 历史查询 / diff 查询委托给 Repository（Controller 直接用） ────────────
    public List<EtlVerifyChunk> listChunks(Long taskId, Long execId) {
        return chunkRepo.findByTaskIdAndExecIdOrderByChunkNoAsc(taskId, execId);
    }

    /** 按 targetTableMap 解析目标表名；委托 {@link TargetTableResolver} 统一实现。 */
    private String resolveTargetTable(SyncTask task, String srcTable) {
        return targetTableResolver.resolve(task, srcTable);
    }

    private String buildSourceWhere(SyncTask task, String dialect, String sourceTable,
                                    Instant windowStart, Instant windowEnd) {
        WatermarkService.WindowContext window = windowStart == null && windowEnd == null
                ? new WatermarkService.WindowContext("FULL", null, null, null, null)
                : new WatermarkService.WindowContext("INCREMENT", windowStart, windowEnd, null, null);
        return buildSourceWhere(task, dialect, sourceTable, window);
    }

    private String buildSourceWhere(SyncTask task, String dialect, String sourceTable,
                                    WatermarkService.WindowContext window) {
        return whereClauseBuilder.build(task, dialect, window, sourceTable);
    }

    // NOTE: 此处与 ValidationRunner.runRowCount 中 validationWhereBuilder.buildSourceWhere 最终
    // 都通过 ValidationSourceFilterBuilder.buildEffectiveSourceWhere 追加软删除排除条件，
    // 修改软删除逻辑时需同步检查两个入口。
    private String buildEffectiveSourceWhere(SyncTask task, String dialect, String sourceTable,
                                             String baseWhere, List<String> sourceColumns) {
        return ValidationSourceFilterBuilder.buildEffectiveSourceWhere(
                task,
                dialect,
                baseWhere,
                sourceColumns,
                whereClauseBuilder,
                dialectQuoteHelper);
    }

    private WatermarkService.WindowContext normalizeWindow(SyncTask task, WatermarkService.WindowContext window) {
        if (window == null) {
            if (isIdRangeIncrementalTask(task)) {
                throw new IllegalStateException(
                        "ID_RANGE CHECKSUM 缺少 windowStartId/windowEndId，无法与 execution window 对齐");
            }
            return new WatermarkService.WindowContext("FULL", null, null, null, null);
        }
        WatermarkService.WindowContext effective = window;
        if ("FULL_THEN_INCREMENT".equalsIgnoreCase(effective.windowType())) {
            return new WatermarkService.WindowContext("FULL", null, null, null, null);
        }
        // ★ 全量校验不需要 ID_RANGE 窗口约束
        if ("FULL".equalsIgnoreCase(effective.windowType())) {
            return effective;
        }
        if (!isIdRangeIncrementalTask(task)) {
            return effective;
        }
        if (!"INCREMENT".equalsIgnoreCase(effective.windowType()) || effective.windowEndId() == null) {
            throw new IllegalStateException(
                    "ID_RANGE CHECKSUM 缺少 windowStartId/windowEndId，无法与 execution window 对齐");
        }
        return effective;
    }

    private boolean isWindowScoped(WatermarkService.WindowContext window) {
        return window != null && window.hasScopedWindow();
    }

    private boolean isIdRangeIncrementalTask(SyncTask task) {
        return task != null
                && "INCREMENTAL".equalsIgnoreCase(task.getDataScope())
                && "ID_RANGE".equalsIgnoreCase(task.getIncrementMode());
    }

    WindowFilter buildTargetTimeWindowFilter(SyncTask task, String sourceTable, WatermarkService.WindowContext window) {
        if (window == null || (window.windowStart() == null && window.windowEnd() == null)) {
            return WindowFilter.NONE;
        }
        if ("ID_RANGE".equalsIgnoreCase(task.getIncrementMode())) {
            return WindowFilter.NONE;
        }
        String incField = task.getIncrementalField();
        if (incField == null || incField.isBlank()) {
            throw new IllegalArgumentException("任务未配置 incrementalField，无法使用 WINDOW 模式 Checksum");
        }
        if (!whereClauseBuilder.isFieldNameSafe(incField)) {
            throw new IllegalArgumentException("非法 incrementalField: " + incField);
        }
        String targetField = targetFieldResolver.resolveTargetColumnSameNameOnly(
                task, sourceTable, incField, "CHECKSUM 目标端时间窗口校验");
        return new WindowFilter(
                dialectQuoteHelper.quoteColumn("DORIS", targetField),
                window.windowStart(),
                window.windowEnd()
        );
    }

    String buildTargetIdRangeWhere(SyncTask task, String sourceTable, WatermarkService.WindowContext window) {
        if (window == null || !"INCREMENT".equalsIgnoreCase(window.windowType())
                || !"ID_RANGE".equalsIgnoreCase(task.getIncrementMode())) {
            return "";
        }
        if (window.windowEndId() == null) {
            throw new IllegalStateException(
                    "ID_RANGE CHECKSUM 缺少 windowStartId/windowEndId，无法与 execution window 对齐");
        }
        String incField = task.getIncrementalField();
        if (incField == null || incField.isBlank()) {
            throw new IllegalArgumentException("任务未配置 incrementalField，无法使用 ID_RANGE Checksum");
        }
        if (!whereClauseBuilder.isFieldNameSafe(incField)) {
            throw new IllegalArgumentException("非法 incrementalField: " + incField);
        }
        String quoted = dialectQuoteHelper.quoteColumn(
                "DORIS",
                targetFieldResolver.resolveTargetColumnSameNameOnly(
                        task, sourceTable, incField, "CHECKSUM 目标端 ID_RANGE 窗口校验")
        );
        StringBuilder sb = new StringBuilder();
        if (window.windowStartId() != null) {
            sb.append(quoted).append(" > ").append(window.windowStartId()).append(" AND ");
        }
        sb.append(quoted).append(" <= ").append(window.windowEndId());
        return sb.toString();
    }

    private boolean isCustomSql(SyncTask task) {
        return task != null && "CUSTOM_SQL".equalsIgnoreCase(task.getSourceMode());
    }

    static boolean chunkRowsMatch(Map<String, String> sourceRows, Map<String, String> targetRows) {
        return sourceRows != null && sourceRows.equals(targetRows);
    }

    static BigInteger[] unionPkRange(BigInteger sourceMin, BigInteger sourceMax,
                                     BigInteger targetMin, BigInteger targetMax) {
        BigInteger min = minNonNull(sourceMin, targetMin);
        BigInteger max = maxNonNull(sourceMax, targetMax);
        return new BigInteger[]{min, max};
    }

    private static BigInteger minNonNull(BigInteger a, BigInteger b) {
        if (a == null) return b;
        if (b == null) return a;
        return a.min(b);
    }

    private static BigInteger maxNonNull(BigInteger a, BigInteger b) {
        if (a == null) return b;
        if (b == null) return a;
        return a.max(b);
    }

    List<String> resolveTargetChecksumColumns(SyncTask task, String sourceTable, List<String> sourceColumns) {
        return targetFieldResolver.resolveTargetColumnsSameNameOnly(
                task, sourceTable, sourceColumns, "CHECKSUM 目标端字段校验");
    }

    private String resolveTargetChecksumColumn(SyncTask task, String sourceTable, String sourceColumn) {
        return targetFieldResolver.resolveTargetColumnSameNameOnly(
                task, sourceTable, sourceColumn, "CHECKSUM 目标端字段校验");
    }

    ChecksumSourcePlan buildChecksumSourcePlan(
            SyncTask task,
            SourceTableResolver.SourceRelation sourceRelation,
            List<SourceDataSourceService.ColumnInfo> sourceColumns,
            List<String> pkCols,
            String sourceWhere) {
        MedicalChecksumOptions medical = medicalChecksumOptions(task);
        if (medical.datasetCode() != null) {
            if (medical.validSourceQuery() != null) {
                MedicalDatasetContract contract = MedicalContractSnapshotCodec.resolveForTask(
                        task, medicalContractService, OBJECT_MAPPER);
                List<String> standardPkCols = contract.primaryKeys().stream()
                        .map(key -> contractField(contract, key).dorisColumn())
                        .toList();
                if (standardPkCols.isEmpty()) {
                    throw new IllegalStateException("医共体 CHECKSUM 缺少规范主键: " + contract.datasetCode());
                }
                List<String> standardColumns = contract.fields().stream()
                        .sorted(java.util.Comparator.comparingInt(field -> field.order() == null ? 0 : field.order()))
                        .map(MedicalFieldContract::dorisColumn)
                        .toList();
                return new ChecksumSourcePlan(
                        "(" + stripTrailingSemicolon(medical.validSourceQuery()) + ") dfetl_src",
                        standardPkCols,
                        standardPkCols.get(0),
                        standardColumns,
                        "");
            }
            if (medicalContractService == null || medicalSourceSelectBuilder == null
                    || sourceDialectAdapterResolver == null) {
                throw new IllegalStateException("医共体 contract-driven CHECKSUM 缺少 SQL 构建依赖");
            }
            MedicalDatasetContract contract = MedicalContractSnapshotCodec.resolveForTask(
                    task, medicalContractService, OBJECT_MAPPER);
            SourceDialectAdapter adapter = sourceDialectAdapterResolver.resolve(
                    sourceRelation.dialect(), medical.compatibilityMode());
            MedicalSourceSelectPlan selectPlan = medicalSourceSelectBuilder.buildSelect(
                    sourceRelation.schema(),
                    sourceRelation.table(),
                    contract,
                    sourceColumns,
                    adapter,
                    medical.fieldMapping());
            if (selectPlan.hasBlockers()) {
                throw new IllegalStateException("医共体 CHECKSUM 源端 SQL 生成失败: " + selectPlan.blockers());
            }
            String query = selectPlan.sql();
            if (sourceWhere != null && !sourceWhere.isBlank()) {
                query += " WHERE " + sourceWhere;
            }
            List<String> standardPkCols = contract.primaryKeys().stream()
                    .map(key -> contractField(contract, key).dorisColumn())
                    .toList();
            if (standardPkCols.isEmpty()) {
                throw new IllegalStateException("医共体 CHECKSUM 缺少规范主键: " + contract.datasetCode());
            }
            List<String> standardColumns = contract.fields().stream()
                    .sorted(java.util.Comparator.comparingInt(field -> field.order() == null ? 0 : field.order()))
                    .map(MedicalFieldContract::dorisColumn)
                    .toList();
            return new ChecksumSourcePlan(
                    "(\n" + query + "\n) dfetl_src",
                    standardPkCols,
                    standardPkCols.get(0),
                    standardColumns,
                    "");
        }

        List<String> srcCols = sourceColumns == null ? List.of() : sourceColumns.stream()
                .map(SourceDataSourceService.ColumnInfo::columnName)
                .toList();
        List<String> sourcePkCols = mapToOriginalCase(pkCols, srcCols);
        if (sourcePkCols.isEmpty()) {
            throw new IllegalStateException("Checksum 需要至少一个源端主键列");
        }
        return new ChecksumSourcePlan(
                dialectQuoteHelper.qualifyTable(sourceRelation.dialect(), sourceRelation.schema(), sourceRelation.table()),
                sourcePkCols,
                sourcePkCols.get(0),
                srcCols,
                sourceWhere == null ? "" : sourceWhere);
    }

    private MedicalFieldContract contractField(MedicalDatasetContract contract, String fieldCodeOrDorisColumn) {
        return contract.fields().stream()
                .filter(field -> field.code().equalsIgnoreCase(fieldCodeOrDorisColumn)
                        || field.dorisColumn().equalsIgnoreCase(fieldCodeOrDorisColumn))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "医共体标准字段不存在: " + contract.datasetCode() + "." + fieldCodeOrDorisColumn));
    }

    private MedicalChecksumOptions medicalChecksumOptions(SyncTask task) {
        if (task == null || task.getDataCharacteristics() == null || task.getDataCharacteristics().isBlank()) {
            return new MedicalChecksumOptions(null, null, Map.of(), null);
        }
        String dc = task.getDataCharacteristics();
        try {
            Map<String, Object> values = OBJECT_MAPPER.readValue(dc, MAP_TYPE);
            Object mode = values.get("medicalMappingMode");
            boolean contractDriven = mode != null && "CONTRACT_DRIVEN".equalsIgnoreCase(mode.toString());
            if (!contractDriven) {
                return new MedicalChecksumOptions(null, null, Map.of(), null);
            }
            Object datasetCode = values.get("matchedDatasetCode");
            if (datasetCode == null || datasetCode.toString().isBlank()) {
                throw new IllegalStateException("医共体 contract-driven 任务缺少 matchedDatasetCode");
            }
            Object compatibilityMode = values.get("compatibilityMode");
            Object validSourceQuery = values.get("medicalValidSourceQuery");
            return new MedicalChecksumOptions(
                    datasetCode.toString().trim(),
                    compatibilityMode == null ? null : compatibilityMode.toString().trim(),
                    parseStringMap(values.get("fieldMapping")),
                    validSourceQuery == null || validSourceQuery.toString().isBlank()
                            ? null
                            : validSourceQuery.toString().trim());
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            if (dc.contains("CONTRACT_DRIVEN")) {
                throw new IllegalStateException("医共体 CHECKSUM 配置解析失败: " + e.getMessage(), e);
            }
            return new MedicalChecksumOptions(null, null, Map.of(), null);
        }
    }

    private void applyExecutionMedicalValidSourceQuery(Long execId, SyncTask task) {
        if (medicalChecksumOptions(task).validSourceQuery() != null || execId == null) {
            return;
        }
        var execution = executionRepo.findById(execId).orElse(null);
        String validSourceQuery = execution == null ? null : execution.getMedicalValidSourceQuery();
        if (validSourceQuery == null || validSourceQuery.isBlank()) {
            return;
        }
        task.setDataCharacteristics(withMedicalValidSourceQuery(task.getDataCharacteristics(), validSourceQuery));
    }

    private static String withMedicalValidSourceQuery(String dataCharacteristics, String validSourceQuery) {
        try {
            Map<String, Object> values = dataCharacteristics == null || dataCharacteristics.isBlank()
                    ? new java.util.LinkedHashMap<>()
                    : OBJECT_MAPPER.readValue(dataCharacteristics, MAP_TYPE);
            values.put("medicalValidSourceQuery", validSourceQuery);
            return OBJECT_MAPPER.writeValueAsString(values);
        } catch (Exception e) {
            throw new IllegalStateException("医共体问题行分流 SQL 写入 ChecksumService dataCharacteristics 失败: "
                    + e.getMessage(), e);
        }
    }

    private static String stripTrailingSemicolon(String sql) {
        String stripped = sql == null ? "" : sql.trim();
        while (stripped.endsWith(";")) {
            stripped = stripped.substring(0, stripped.length() - 1).trim();
        }
        return stripped;
    }

    private static Map<String, String> parseStringMap(Object value) {
        if (value == null) {
            return Map.of();
        }
        if (!(value instanceof Map<?, ?> raw)) {
            return Map.of();
        }
        return raw.entrySet().stream()
                .filter(entry -> entry.getKey() != null && entry.getValue() != null)
                .collect(Collectors.toMap(
                        entry -> entry.getKey().toString(),
                        entry -> entry.getValue().toString(),
                        (first, second) -> second));
    }

    static String buildReadChunkSql(String dialect, String schema, String table,
                                    List<String> pkCols, String firstPk, List<String> cols,
                                    ChunkPlanner.Chunk c,
                                    WindowFilter window) {
        return buildReadChunkSql(dialect, schema, table, pkCols, firstPk, cols, c, "", window);
    }

    static String buildReadChunkSql(String dialect, String schema, String table,
                                    List<String> pkCols, String firstPk, List<String> cols,
                                    ChunkPlanner.Chunk c,
                                    String whereClause,
                                    WindowFilter window) {
        DialectQuoteHelper quoteHelper = new DialectQuoteHelper();
        return buildReadChunkSqlFrom(
                dialect,
                quoteHelper.qualifyTable(dialect, schema, table),
                pkCols,
                firstPk,
                cols,
                c,
                whereClause,
                window);
    }

    static String buildReadChunkSqlFrom(String dialect, String fromSql,
                                        List<String> pkCols, String firstPk, List<String> cols,
                                        ChunkPlanner.Chunk c,
                                        String whereClause,
                                        WindowFilter window) {
        DialectQuoteHelper quoteHelper = new DialectQuoteHelper();
        StringBuilder sb = new StringBuilder("SELECT ");
        for (int i = 0; i < pkCols.size(); i++) {
            if (i > 0) sb.append(", ");
            sb.append(quoteHelper.quoteColumn(dialect, pkCols.get(i)));
        }
        Set<String> pkSet = new HashSet<>();
        for (String p : pkCols) pkSet.add(p.toLowerCase(Locale.ROOT));
        for (String col : cols) {
            if (pkSet.contains(col.toLowerCase(Locale.ROOT))) continue;
            sb.append(", ").append(quoteHelper.quoteColumn(dialect, col));
        }
        sb.append(" FROM ").append(fromSql);
        boolean ranged = c.start() != null && c.end() != null;
        if (ranged) {
            sb.append(" WHERE ").append(quoteHelper.quoteColumn(dialect, firstPk)).append(" BETWEEN ? AND ?");
        }
        boolean hasWhere = appendRawWhere(sb, ranged, whereClause);
        window.appendWhere(sb, hasWhere);
        return sb.toString();
    }

    static String buildCountRowsSql(String dialect, String schema, String table,
                                    BigInteger start, BigInteger end, String pkCol,
                                    WindowFilter window) {
        return buildCountRowsSql(dialect, schema, table, start, end, pkCol, "", window);
    }

    static String buildCountRowsSql(String dialect, String schema, String table,
                                    BigInteger start, BigInteger end, String pkCol,
                                    String whereClause,
                                    WindowFilter window) {
        DialectQuoteHelper quoteHelper = new DialectQuoteHelper();
        return buildCountRowsSqlFrom(
                dialect,
                quoteHelper.qualifyTable(dialect, schema, table),
                start,
                end,
                pkCol,
                whereClause,
                window);
    }

    static String buildCountRowsSqlFrom(String dialect, String fromSql,
                                        BigInteger start, BigInteger end, String pkCol,
                                        String whereClause,
                                        WindowFilter window) {
        DialectQuoteHelper quoteHelper = new DialectQuoteHelper();
        StringBuilder sb = new StringBuilder("SELECT COUNT(*) FROM ")
                .append(fromSql);
        boolean hasPk = start != null && end != null;
        if (hasPk) {
            sb.append(" WHERE ").append(quoteHelper.quoteColumn(dialect, pkCol)).append(" BETWEEN ? AND ?");
        }
        boolean hasWhere = appendRawWhere(sb, hasPk, whereClause);
        window.appendWhere(sb, hasWhere);
        return sb.toString();
    }

    static String buildMinMaxPkSql(String dialect, String schema, String table,
                                   String pkCol, WindowFilter window) {
        return buildMinMaxPkSql(dialect, schema, table, pkCol, "", window);
    }

    static String buildMinMaxPkSql(String dialect, String schema, String table,
                                   String pkCol, String whereClause, WindowFilter window) {
        DialectQuoteHelper quoteHelper = new DialectQuoteHelper();
        return buildMinMaxPkSqlFrom(
                dialect,
                quoteHelper.qualifyTable(dialect, schema, table),
                pkCol,
                whereClause,
                window);
    }

    static String buildMinMaxPkSqlFrom(String dialect, String fromSql,
                                       String pkCol, String whereClause, WindowFilter window) {
        DialectQuoteHelper quoteHelper = new DialectQuoteHelper();
        String quotedPk = quoteHelper.quoteColumn(dialect, pkCol);
        StringBuilder sb = new StringBuilder("SELECT MIN(").append(quotedPk)
                .append("), MAX(").append(quotedPk).append(") FROM ")
                .append(fromSql);
        boolean hasWhere = appendRawWhere(sb, false, whereClause);
        window.appendWhere(sb, hasWhere);
        return sb.toString();
    }

    /**
     * 将列名列表映射回 JDBC 元数据中的原始大小写。
     * 任务配置中的列名可能是小写（如 yiliaojgdm），但源端 JDBC 返回的可能是大写（如 YILIAOJGDM）。
     * 对于 PostgreSQL 双引号创建的列名，必须使用原始大小写才能正确引用。
     */
    private static List<String> mapToOriginalCase(List<String> cols, List<String> jdbcCols) {
        // 构建小写 → 原始大小写的映射
        java.util.Map<String, String> lowerToOriginal = new java.util.HashMap<>();
        for (String jc : jdbcCols) {
            lowerToOriginal.put(jc.toLowerCase(Locale.ROOT), jc);
        }
        List<String> result = new java.util.ArrayList<>(cols.size());
        for (String col : cols) {
            String original = lowerToOriginal.get(col.toLowerCase(Locale.ROOT));
            result.add(original != null ? original : col);
        }
        return result;
    }

    private static boolean appendRawWhere(StringBuilder sb, boolean alreadyHasWhere, String whereClause) {
        if (whereClause == null || whereClause.isBlank()) {
            return alreadyHasWhere;
        }
        String trimmed = whereClause.trim();
        if (trimmed.regionMatches(true, 0, "WHERE ", 0, 6)) {
            trimmed = trimmed.substring(6).trim();
        }
        if (trimmed.isBlank()) {
            return alreadyHasWhere;
        }
        sb.append(alreadyHasWhere ? " AND (" : " WHERE ");
        sb.append(trimmed);
        if (alreadyHasWhere) {
            sb.append(")");
        }
        return true;
    }
}
