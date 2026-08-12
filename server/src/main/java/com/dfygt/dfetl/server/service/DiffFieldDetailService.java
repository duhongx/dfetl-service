package com.dfygt.dfetl.server.service;

import com.dfygt.dfetl.server.common.AesUtil;
import com.dfygt.dfetl.server.config.DiffProperties;
import com.dfygt.dfetl.server.engine.checksum.HashCodec;
import com.dfygt.dfetl.server.engine.checksum.RowNormalizer;
import com.dfygt.dfetl.server.entity.EtlVerifyDiff;
import com.dfygt.dfetl.server.entity.EtlVerifyDiffField;
import com.dfygt.dfetl.server.entity.SourceDataSource;
import com.dfygt.dfetl.server.entity.SyncTask;
import com.dfygt.dfetl.server.entity.TargetDataSource;
import com.dfygt.dfetl.server.repository.EtlVerifyDiffFieldRepository;
import com.dfygt.dfetl.server.repository.EtlVerifyDiffRepository;
import com.dfygt.dfetl.server.repository.SourceDataSourceRepository;
import com.dfygt.dfetl.server.repository.SyncTaskRepository;
import com.dfygt.dfetl.server.repository.TargetDataSourceRepository;
import com.dfygt.dfetl.server.service.validation.ValidationWhereBuilder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.regex.Pattern;

/**
 * Spec 055 — 字段级差异实时展开（Phase A）。
 *
 * <p>输入：{@code etl_verify_diff.id}（PK 级差异行）。
 * <p>输出：按列对比的 {@link FieldDiffReport}，含双端展示值、diffKind、脱敏标记。
 *
 * <p>实时回读源/目标库（不预先落库），与校验时刻可能存在数据漂移，
 * {@code possiblyChanged} 标记由 {@link DiffProperties#staleThresholdSeconds()} 控制。
 *
 * <p>安全约束：
 * <ul>
 *   <li>所有标识符（schema/table/column/pkCol）通过 {@link WhereClauseBuilder#isFieldNameSafe(String)} 白名单校验</li>
 *   <li>列名按方言 quote（Oracle/SQLServer 大小写敏感）</li>
 *   <li>PK 值用 {@code PreparedStatement.setObject} 绑定，绝不字符串拼接</li>
 *   <li>命中 {@link DiffProperties#maskColumnPatterns()} 的列在归一化前替换为 {@code "***"}</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DiffFieldDetailService {

    private final EtlVerifyDiffRepository diffRepo;
    private final EtlVerifyDiffFieldRepository diffFieldRepo;
    private final SyncTaskRepository syncTaskRepo;
    private final TargetDataSourceRepository targetDsRepo;
    private final SourceDataSourceRepository sourceDsRepo;
    private final SourceDataSourceService sourceDataSourceService;
    private final WhereClauseBuilder whereClauseBuilder;
    private final DiffProperties props;
    private final AesUtil aesUtil;
    private final SourceTableResolver sourceTableResolver;
    private final DialectQuoteHelper dialectQuoteHelper;
    private final TargetTableResolver targetTableResolver;
    private final TargetFieldResolver targetFieldResolver;
    private final com.dfygt.dfetl.server.common.JdbcConnectionPoolManager connectionPoolManager;
    private final ValidationWhereBuilder validationWhereBuilder;

    // ── 返回结构 ─────────────────────────────────────────────────────────

    /**
     * 单列差异详情。
     *
     * @param column            列名（以源端为基准）
     * @param targetColumn      目标端列名（spec 055 Phase A：默认 = column.toLowerCase()）
     * @param diffKind          VALUE_DIFF | MISSING_IN_TARGET | EXTRA_IN_TARGET | EQUAL
     * @param srcValueDisplay   脱敏 + 截断后的展示值
     * @param tgtValueDisplay   同上
     * @param masked            true 时该列命中 maskColumnPatterns
     * @param truncated         原始值超过 maxValueLength 被截断
     * @param normalizedDiffer  raw 看似一致但 normalized 不同
     */
    public record FieldDiff(
            String column,
            String targetColumn,
            String diffKind,
            String srcValueDisplay,
            String tgtValueDisplay,
            boolean masked,
            boolean truncated,
            boolean normalizedDiffer
    ) {
        public static final String VALUE_DIFF        = "VALUE_DIFF";
        public static final String MISSING_IN_TARGET = "MISSING_IN_TARGET";
        public static final String EXTRA_IN_TARGET   = "EXTRA_IN_TARGET";
        public static final String EQUAL             = "EQUAL";
    }

    public record FieldDiffReport(
            Long diffId,
            Long taskId,
            String pkValue,
            String diffType,
            OffsetDateTime checksumAt,
            OffsetDateTime queriedAt,
            boolean possiblyChanged,
            boolean srcRowExists,
            boolean tgtRowExists,
            List<FieldDiff> fields,
            String warning
    ) {}

    // ── 主入口 ────────────────────────────────────────────────────────────

    /**
     * 字段级差异主入口（cache-first）：
     * <ol>
     *   <li>命中 spec 056 预计算缓存 → 直接组装返回（秒开）</li>
     *   <li>未命中 → 走 spec 055 实时回读源/目标库</li>
     * </ol>
     *
     * @param diffId    etl_verify_diff.id
     * @param showEqual true 时包含 EQUAL 列；默认 false 仅返回差异列
     */
    public FieldDiffReport detail(Long diffId, boolean showEqual) {
        EtlVerifyDiff diff = diffRepo.findById(diffId)
                .orElseThrow(() -> new NoSuchElementException("EtlVerifyDiff not found: " + diffId));
        // spec 056：cache-first
        List<EtlVerifyDiffField> cached = diffFieldRepo.findByDiffIdOrderByIdAsc(diffId);
        if (!cached.isEmpty()) {
            // spec validation-workbench-redesign bugfix：当 showEqual=true 但缓存里没有 EQUAL 行时
            // （precomputeIncludeEqual=false 导致预计算只存差异字段），必须 fallback 到实时回读路径，
            // 否则用户打开「显示一致字段」Switch 后看不到任何 EQUAL 字段。
            boolean cacheHasEqual = cached.stream()
                    .anyMatch(f -> FieldDiff.EQUAL.equals(f.getDiffKind()));
            if (showEqual && !cacheHasEqual) {
                // 缓存不含 EQUAL 行，走实时回读以获取全部字段
                return detailRealtime(diff, true).report();
            }
            assertNoUnsupportedRenameForCachedDiff(diff, cached);
            return assembleFromCache(diff, cached, showEqual);
        }
        return detailRealtime(diff, showEqual).report();
    }

    /**
     * spec validation-workbench-redesign · Task P0-2.3 / Requirement 3 (AC 8)：
     * 不传 showEqual 时默认 true，确保 INSERT_MISSING / DELETE_MISSING 行能回读全部字段。
     *
     * <p>背景：原 detail(diffId, showEqual) 在 INSERT_MISSING 行回读时，仅 showEqual=true
     * 才返回所有列（diffKind 全是 MISSING_IN_TARGET）；showEqual=false 则被默认过滤为空集，
     * 导致前端「查看源端值」面板看不到任何字段。前端默认调用此重载即可。
     */
    public FieldDiffReport detail(Long diffId) {
        return detail(diffId, true);
    }

    /**
     * Spec 056 预计算专用入口：跳过 cache，强制走实时，并返回带 hash 的内部结构供入库。
     *
     * <p>{@code includeEqual} 由 {@link DiffProperties#precomputeIncludeEqual()} 决定。
     */
    public InternalReport detailInternal(Long diffId, boolean includeEqual) {
        EtlVerifyDiff diff = diffRepo.findById(diffId)
                .orElseThrow(() -> new NoSuchElementException("EtlVerifyDiff not found: " + diffId));
        return detailRealtime(diff, includeEqual);
    }

    /** Spec 056：实时计算的内部产物，封装 (report, hashes)。 */
    public record InternalReport(FieldDiffReport report, List<FieldHash> hashes) {}

    /** Spec 056：与 report.fields 一一对应的归一化 hash（不含原值）。 */
    public record FieldHash(String column, String srcValueHash, String tgtValueHash) {}

    /** spec 055 原行为：实时回读双端，逐列对比，返回带 hash 的内部结构。 */
    private InternalReport detailRealtime(EtlVerifyDiff diff, boolean showEqual) {
        Long diffId = diff.getId();
        SyncTask task = syncTaskRepo.findById(diff.getTaskId())
                .orElseThrow(() -> new NoSuchElementException("SyncTask not found: " + diff.getTaskId()));

        // 1. 安全校验：schema / table / pkCols
        if (task.getViewNames() == null || task.getViewNames().isEmpty()) {
            throw new IllegalArgumentException("任务未配置 viewNames");
        }
        if (task.getViewNames().size() != 1) {
            throw new IllegalArgumentException("Field diff v1 仅支持单表/单视图任务");
        }
        String srcTable = task.getViewNames().get(0);
        SourceDataSource srcDs = sourceDsRepo.findById(task.getSourceDataSourceId())
                .orElseThrow(() -> new NoSuchElementException("SourceDataSource not found"));
        SourceTableResolver.SourceRelation sourceRelation =
                sourceTableResolver.resolveRequired(task, srcDs, srcTable);
        String srcSchema = sourceRelation.schema();
        srcTable = sourceRelation.table();

        List<String> pkCols = resolvePkCols(task);

        // 2. 解析 PK 值：新复合 PK 使用 JSON array；保留历史 "|" 格式只读兼容。
        String[] pkValues = PkValueCodec.decode(diff.getPkValue(), pkCols.size()).toArray(String[]::new);

        // 3. 列清单
        List<String> srcCols = sourceDataSourceService
                .listColumns(task.getSourceDataSourceId(), srcSchema, srcTable)
                .stream().map(c -> c.columnName()).toList();
        if (srcCols.isEmpty()) {
            throw new IllegalStateException("源表无可用列");
        }
        for (String c : srcCols) {
            if (!whereClauseBuilder.isFieldNameSafe(c)) {
                throw new IllegalArgumentException("非法列名: " + c);
            }
        }
        List<String> srcPkCols = resolveActualSourceColumns(pkCols, srcCols, "字段级 diff 主键");

        boolean tooManyCols = srcCols.size() > props.maxColumnsPerRow();
        if (tooManyCols) {
            srcCols = srcCols.subList(0, props.maxColumnsPerRow());
        }
        targetFieldResolver.assertNoUnsupportedRename(
                task, srcTable, pkCols, "字段级 diff 主键校验");
        targetFieldResolver.assertNoUnsupportedRename(
                task, srcTable, srcCols, "字段级 diff 字段校验");

        String srcDbType = sourceRelation.dialect();

        TargetDataSource tgt = targetDsRepo.findById(task.getTargetDataSourceId())
                .orElseThrow(() -> new NoSuchElementException("TargetDataSource not found"));
        // Doris 表名按 TargetTableResolver 规则：targetTableMap 优先；缺省则 srcTable.toLowerCase()
        String tgtTable = targetTableResolver.resolve(task, srcTable);
        List<String> tgtCols = srcCols.stream().map(c -> c.toLowerCase(Locale.ROOT)).toList();

        log.info("DiffFieldDetail.detail diffId={} taskId={} pk={} diffType={} srcCols={} tooMany={}",
                diffId, task.getId(), diff.getPkValue(), diff.getDiffType(), srcCols.size(), tooManyCols);

        // 4. 根据 diff_type 决定查询哪一端
        String diffType = diff.getDiffType();
        Map<String, Object> srcRow = null;
        Map<String, Object> tgtRow = null;
        OffsetDateTime queriedAt = OffsetDateTime.now();

        if (EtlVerifyDiff.TYPE_INSERT_MISSING.equals(diffType)
                || EtlVerifyDiff.TYPE_UPDATE_DIFF.equals(diffType)) {
            try (Connection srcConn = sourceDataSourceService.openConnection(task.getSourceDataSourceId())) {
                String sourceScopeFilter = validationWhereBuilder.buildSourceWhere(
                        null, task, srcDbType, srcTable, srcCols);
                srcRow = fetchRow(srcConn, srcDbType, srcSchema, srcTable, srcPkCols, pkValues,
                        srcCols, sourceScopeFilter);
            } catch (Exception e) {
                throw new RuntimeException("读取源端数据失败: " + e.getMessage(), e);
            }
        }
        if (EtlVerifyDiff.TYPE_DELETE_MISSING.equals(diffType)
                || EtlVerifyDiff.TYPE_UPDATE_DIFF.equals(diffType)) {
            try (Connection tgtConn = openDorisConn(tgt)) {
                String targetScopeFilter = validationWhereBuilder.isTenantScopeFilterActive(task)
                        ? "`" + ValidationWhereBuilder.ETL_JOB_ID_COL + "` = ?"
                        : null;
                tgtRow = fetchRow(tgtConn, "DORIS", tgt.getDbName(), tgtTable,
                        pkCols.stream().map(p -> p.toLowerCase(Locale.ROOT)).toList(),
                        pkValues, tgtCols, targetScopeFilter,
                        targetScopeFilter == null ? new Object[0] : new Object[]{task.getId()});
            } catch (Exception e) {
                throw new RuntimeException("读取目标端数据失败: " + e.getMessage(), e);
            }
        }

        // 5. 组装字段差异
        RowNormalizer normalizer = new RowNormalizer();
        HashCodec codec = new HashCodec(HashCodec.Algo.MD5);
        List<Pattern> maskPatterns = props.compiledMaskPatterns();

        List<FieldDiff> fields = new ArrayList<>();
        List<FieldHash> hashes = new ArrayList<>();
        for (int i = 0; i < srcCols.size(); i++) {
            String srcCol = srcCols.get(i);
            String tgtCol = tgtCols.get(i);
            boolean masked = isMasked(srcCol, maskPatterns);

            Object srcVal = srcRow != null ? srcRow.get(srcCol.toLowerCase(Locale.ROOT)) : null;
            Object tgtVal = tgtRow != null ? tgtRow.get(tgtCol.toLowerCase(Locale.ROOT)) : null;

            // 脱敏：在归一化和展示前覆盖
            String srcRaw = masked ? "***" : truncateValue(safeString(srcVal));
            String tgtRaw = masked ? "***" : truncateValue(safeString(tgtVal));

            String srcNorm = masked ? "***" : normalizer.normalize(srcVal);
            String tgtNorm = masked ? "***" : normalizer.normalize(tgtVal);

            String kind;
            if (srcRow == null && tgtRow != null) {
                // INSERT_MISSING 走过：srcRow 不为空，所以这里不会到
                kind = FieldDiff.MISSING_IN_TARGET;
            } else if (srcRow != null && tgtRow == null) {
                kind = FieldDiff.MISSING_IN_TARGET;
            } else if (srcRow == null) {
                kind = FieldDiff.EXTRA_IN_TARGET;
            } else if (java.util.Objects.equals(srcNorm, tgtNorm)) {
                kind = FieldDiff.EQUAL;
            } else {
                kind = FieldDiff.VALUE_DIFF;
            }

            // DELETE_MISSING：源不存在视角下，每列都是 EXTRA_IN_TARGET
            if (EtlVerifyDiff.TYPE_DELETE_MISSING.equals(diffType)) {
                kind = FieldDiff.EXTRA_IN_TARGET;
            } else if (EtlVerifyDiff.TYPE_INSERT_MISSING.equals(diffType)) {
                kind = FieldDiff.MISSING_IN_TARGET;
            }

            if (!showEqual && FieldDiff.EQUAL.equals(kind)) continue;

            boolean truncated = !masked && (safeString(srcVal).length() > props.maxValueLength()
                    || safeString(tgtVal).length() > props.maxValueLength());
            boolean normalizedDiffer = !masked
                    && java.util.Objects.equals(srcRaw, tgtRaw)
                    && !java.util.Objects.equals(srcNorm, tgtNorm);

            fields.add(new FieldDiff(srcCol, tgtCol, kind, srcRaw, tgtRaw, masked, truncated, normalizedDiffer));
            hashes.add(new FieldHash(srcCol,
                    srcNorm == null ? null : codec.hash(srcNorm),
                    tgtNorm == null ? null : codec.hash(tgtNorm)));
        }

        // 6. 组装报告
        boolean srcRowExists = srcRow != null;
        boolean tgtRowExists = tgtRow != null;
        OffsetDateTime checksumAt = diff.getDetectedAt();
        boolean possiblyChanged = checksumAt != null
                && ChronoUnit.SECONDS.between(checksumAt, queriedAt) > props.staleThresholdSeconds();

        String warning = buildWarning(diffType, srcRowExists, tgtRowExists, tooManyCols);

        FieldDiffReport report = new FieldDiffReport(
                diffId, task.getId(), diff.getPkValue(), diffType,
                checksumAt, queriedAt, possiblyChanged,
                srcRowExists, tgtRowExists,
                fields, warning);
        return new InternalReport(report, hashes);
    }

    // ── 私有方法 ──────────────────────────────────────────────────────────

    private void assertNoUnsupportedRenameForCachedDiff(EtlVerifyDiff diff, List<EtlVerifyDiffField> cached) {
        SyncTask task = syncTaskRepo.findById(diff.getTaskId())
                .orElseThrow(() -> new NoSuchElementException("SyncTask not found: " + diff.getTaskId()));
        if (task.getViewNames() == null || task.getViewNames().isEmpty()) {
            throw new IllegalArgumentException("任务未配置 viewNames");
        }
        if (task.getViewNames().size() != 1) {
            throw new IllegalArgumentException("Field diff v1 仅支持单表/单视图任务");
        }
        String sourceTable = task.getViewNames().get(0);
        targetFieldResolver.assertNoUnsupportedRename(
                task, sourceTable, resolvePkCols(task), "字段级 diff 缓存主键校验");
        List<String> cachedColumns = cached.stream()
                .map(EtlVerifyDiffField::getColumnName)
                .filter(column -> column != null && !column.isBlank())
                .toList();
        targetFieldResolver.assertNoUnsupportedRename(
                task, sourceTable, cachedColumns, "字段级 diff 缓存字段校验");
    }

    /**
     * 单行 SELECT；返回 {@code (列名小写 -> 值)} 映射，PK 值用 PreparedStatement.setObject 绑定。
     * 列名按方言 quote。
     */
    private Map<String, Object> fetchRow(Connection conn, String dbType,
                                         String schema, String table,
                                         List<String> pkCols, String[] pkValues,
                                         List<String> cols) throws Exception {
        return fetchRow(conn, dbType, schema, table, pkCols, pkValues, cols, null);
    }

    private Map<String, Object> fetchRow(Connection conn, String dbType,
                                         String schema, String table,
                                         List<String> pkCols, String[] pkValues,
                                         List<String> cols,
                                         String extraWhere,
                                         Object... extraParams) throws Exception {
        String sql = buildFetchRowSql(dbType, schema, table, pkCols, cols, extraWhere);

        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            for (int i = 0; i < pkValues.length; i++) {
                ps.setObject(i + 1, pkValues[i]);
            }
            int offset = pkValues.length;
            if (extraParams != null) {
                for (int i = 0; i < extraParams.length; i++) {
                    ps.setObject(offset + i + 1, extraParams[i]);
                }
            }
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return null;
                ResultSetMetaData md = rs.getMetaData();
                int n = md.getColumnCount();
                Map<String, Object> row = new LinkedHashMap<>(n);
                for (int i = 1; i <= n; i++) {
                    row.put(md.getColumnLabel(i).toLowerCase(Locale.ROOT), rs.getObject(i));
                }
                return row;
            }
        }
    }

    /** 返回字段差异行身份列。优先使用真实业务键 upsertKeys，splitPk 仅作兼容 fallback。 */
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
            throw new IllegalArgumentException("Field diff 需要 upsertKeys 或 splitPk 配置主键列");
        }
        if (!whereClauseBuilder.isFieldNameSafe(splitPk)) {
            throw new IllegalArgumentException("非法 splitPk: " + splitPk);
        }
        return List.of(splitPk);
    }

    private List<String> resolveActualSourceColumns(
            List<String> requestedColumns, List<String> actualColumns, String purpose) {
        return RequiredColumnResolver.resolveUnique(requestedColumns, actualColumns, purpose);
    }

    private String quoteIdent(String ident, String dbType) {
        return dialectQuoteHelper.quoteIdentifier(dbType, ident);
    }

    private String qualifyTable(String dbType, String schema, String table) {
        return dialectQuoteHelper.qualifyTable(dbType, schema, table);
    }

    private boolean isMasked(String column, List<Pattern> patterns) {
        for (Pattern p : patterns) {
            if (p.matcher(column).matches()) return true;
        }
        return false;
    }

    private String safeString(Object v) {
        if (v == null) return "";
        if (v instanceof byte[] bs) return "<binary length=" + bs.length + ">";
        return v.toString();
    }

    private String truncateValue(String s) {
        if (s == null) return "";
        int max = props.maxValueLength();
        if (s.length() <= max) return s;
        return s.substring(0, max) + "...(+" + (s.length() - max) + ")";
    }

    private String buildWarning(String diffType, boolean srcExists, boolean tgtExists, boolean tooManyCols) {
        List<String> warnings = new ArrayList<>();
        if (tooManyCols) {
            warnings.add("列数过多，仅展示前 " + props.maxColumnsPerRow() + " 列");
        }
        if (EtlVerifyDiff.TYPE_INSERT_MISSING.equals(diffType) && !srcExists) {
            warnings.add("源端记录已不存在，可能在校验后被删除");
        }
        if (EtlVerifyDiff.TYPE_DELETE_MISSING.equals(diffType) && !tgtExists) {
            warnings.add("目标端记录已不存在，可能在校验后被修复");
        }
        if (EtlVerifyDiff.TYPE_UPDATE_DIFF.equals(diffType)) {
            if (!srcExists) warnings.add("源端记录已不存在");
            if (!tgtExists) warnings.add("目标端记录已不存在");
        }
        return warnings.isEmpty() ? null : String.join("; ", warnings);
    }

    private Connection openDorisConn(TargetDataSource tgt) throws Exception {
        String url = "jdbc:mysql://" + tgt.getFeHost() + ":" + tgt.getFePort() + "/" + tgt.getDbName()
                + "?useSSL=false&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true";
        return connectionPoolManager.getConnection(url, tgt.getUsername(), aesUtil.decrypt(tgt.getPasswordEnc()));
    }

    static String buildFetchRowSql(String dbType, String schema, String table,
                                   List<String> pkCols, List<String> cols) {
        return buildFetchRowSql(dbType, schema, table, pkCols, cols, null);
    }

    static String buildFetchRowSql(String dbType, String schema, String table,
                                   List<String> pkCols, List<String> cols,
                                   String extraWhere) {
        DialectQuoteHelper quoteHelper = new DialectQuoteHelper();
        String dialect = dbType == null ? "MYSQL" : dbType.toUpperCase(Locale.ROOT);
        StringBuilder sb = new StringBuilder("SELECT ");
        for (int i = 0; i < cols.size(); i++) {
            if (i > 0) sb.append(", ");
            sb.append(quoteHelper.quoteColumn(dialect, cols.get(i)));
        }
        sb.append(" FROM ").append(quoteHelper.qualifyTable(dialect, schema, table));
        sb.append(" WHERE ");
        for (int i = 0; i < pkCols.size(); i++) {
            if (i > 0) sb.append(" AND ");
            sb.append(quoteHelper.quoteColumn(dialect, pkCols.get(i))).append(" = ?");
        }
        if (extraWhere != null && !extraWhere.isBlank()) {
            sb.append(" AND ").append(extraWhere);
        }
        if ("ORACLE".equals(dialect)) {
            sb.append(" FETCH FIRST 1 ROWS ONLY");
        } else {
            sb.append(" LIMIT 1");
        }
        return sb.toString();
    }

    // ── spec 056：cache-first 组装 ─────────────────────────────────────────

    /**
     * 命中 etl_verify_diff_field 缓存时直接组装 FieldDiffReport，不再回读源/目标库。
     *
     * <p>{@code queriedAt} = 缓存第一行的 created_at（即"预计算时刻"）；
     * {@code possiblyChanged} 仍按 {@code now() - diff.detectedAt} 与 {@link DiffProperties#staleThresholdSeconds()} 比较。
     */
    private FieldDiffReport assembleFromCache(EtlVerifyDiff diff,
                                              List<EtlVerifyDiffField> cached,
                                              boolean showEqual) {
        OffsetDateTime queriedAt = cached.get(0).getCreatedAt();
        OffsetDateTime checksumAt = diff.getDetectedAt();
        boolean possiblyChanged = checksumAt != null
                && ChronoUnit.SECONDS.between(checksumAt, OffsetDateTime.now()) > props.staleThresholdSeconds();

        // 缓存内容已是脱敏 + 归一化后的，按 diff_type 推断双端存在性（与实时口径一致）
        String diffType = diff.getDiffType();
        boolean srcRowExists = !EtlVerifyDiff.TYPE_DELETE_MISSING.equals(diffType);
        boolean tgtRowExists = !EtlVerifyDiff.TYPE_INSERT_MISSING.equals(diffType);

        List<FieldDiff> fields = new ArrayList<>(cached.size());
        for (EtlVerifyDiffField f : cached) {
            if (!showEqual && FieldDiff.EQUAL.equals(f.getDiffKind())) continue;
            fields.add(new FieldDiff(
                    f.getColumnName(),
                    f.getTargetColumn(),
                    f.getDiffKind(),
                    f.getSrcValueDisplay() == null ? "" : f.getSrcValueDisplay(),
                    f.getTgtValueDisplay() == null ? "" : f.getTgtValueDisplay(),
                    f.isMasked(),
                    f.isTruncated(),
                    f.isNormalizedDiffer()
            ));
        }
        String warning = "[预计算缓存命中] 数据为预计算时刻快照，可能与最新源/目标值存在漂移";
        return new FieldDiffReport(
                diff.getId(), diff.getTaskId(), diff.getPkValue(), diffType,
                checksumAt, queriedAt, possiblyChanged,
                srcRowExists, tgtRowExists,
                fields, warning);
    }
}
