package com.dfygt.dfetl.server.medical.precheck;

import com.dfygt.dfetl.server.common.AesUtil;
import com.dfygt.dfetl.server.common.ExecutionErrorSanitizer;
import com.dfygt.dfetl.server.common.IdentifierSanitizer;
import com.dfygt.dfetl.server.common.JdbcConnectionPoolManager;
import com.dfygt.dfetl.server.dto.DfetlPrecheckIssueQuery;
import com.dfygt.dfetl.server.entity.DfetlPrecheckRun;
import com.dfygt.dfetl.server.entity.InstitutionDatasetRoute;
import com.dfygt.dfetl.server.entity.TargetDataSource;
import com.dfygt.dfetl.server.repository.DfetlPrecheckRunRepository;
import com.dfygt.dfetl.server.repository.InstitutionDatasetRouteRepository;
import com.dfygt.dfetl.server.repository.TargetDataSourceRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.regex.Pattern;

/** Doris 预检问题与原始行的 JDBC 导出实现。 */
@Service
@RequiredArgsConstructor
public class JdbcDorisPrecheckExportGateway implements DorisPrecheckExportGateway {

    private static final List<String> ISSUE_COLUMNS = List.of(
            "row_id", "row_hash", "business_pk", "field_code", "error_type", "severity",
            "raw_value", "normalized_value", "standard_rule", "error_message");
    private static final Set<String> RAW_TECHNICAL_COLUMNS = Set.of(
            "run_id", "row_id", "row_hash", "loaded_at");
    private static final Pattern PROPERTY_KEY = Pattern.compile("^[A-Za-z0-9._-]+$");

    private final DfetlPrecheckRunRepository runRepository;
    private final InstitutionDatasetRouteRepository routeRepository;
    private final TargetDataSourceRepository targetRepository;
    private final AesUtil aesUtil;
    private final JdbcConnectionPoolManager connectionPoolManager;

    @Override
    public ExportSchema schema(Long runId, DfetlPrecheckIssueQuery query) {
        QueryContext context = context(runId);
        List<Object> parameters = new ArrayList<>();
        String predicate = predicate(runId, query, parameters, "");
        String countSql = "SELECT COUNT(*) FROM " + qualified(context.database(), "precheck_issue")
                + " WHERE " + predicate;
        String columnsSql = "SELECT column_name FROM information_schema.columns"
                + " WHERE table_schema=? AND table_name=? ORDER BY ordinal_position";
        try (Connection connection = openConnection(context.target())) {
            long count;
            try (PreparedStatement statement = connection.prepareStatement(countSql)) {
                bind(statement, parameters);
                try (ResultSet resultSet = statement.executeQuery()) {
                    if (!resultSet.next()) {
                        throw new IllegalStateException("Doris 未返回预检问题总数");
                    }
                    count = resultSet.getLong(1);
                }
            }
            List<String> rawColumns = new ArrayList<>();
            try (PreparedStatement statement = connection.prepareStatement(columnsSql)) {
                statement.setString(1, context.database());
                statement.setString(2, context.rawTable());
                try (ResultSet resultSet = statement.executeQuery()) {
                    while (resultSet.next()) {
                        String column = IdentifierSanitizer.requireValid(
                                resultSet.getString(1), "rawColumn");
                        if (!RAW_TECHNICAL_COLUMNS.contains(column.toLowerCase(Locale.ROOT))) {
                            rawColumns.add(column);
                        }
                    }
                }
            }
            List<String> headers = new ArrayList<>(ISSUE_COLUMNS);
            rawColumns.forEach(column -> headers.add("source_" + column));
            return new ExportSchema(count, headers, rawColumns);
        } catch (Exception e) {
            throw new IllegalStateException("读取 Doris 预检导出结构失败: " + safe(e), e);
        }
    }

    @Override
    public List<List<String>> readPage(
            Long runId,
            DfetlPrecheckIssueQuery query,
            ExportSchema schema,
            long offset,
            int limit) {
        if (offset < 0 || limit <= 0) {
            throw new IllegalArgumentException("导出分页参数必须有效");
        }
        QueryContext context = context(runId);
        List<Object> parameters = new ArrayList<>();
        String predicate = predicate(runId, query, parameters, "i.");
        String sql = selectSql(context, schema.rawColumns(), predicate)
                + " ORDER BY i.row_id,i.field_code,i.error_type LIMIT ? OFFSET ?";
        try (Connection connection = openConnection(context.target());
             PreparedStatement statement = connection.prepareStatement(sql)) {
            int index = bind(statement, parameters);
            statement.setInt(index++, limit);
            statement.setLong(index, offset);
            try (ResultSet resultSet = statement.executeQuery()) {
                List<List<String>> rows = new ArrayList<>();
                while (resultSet.next()) {
                    List<String> row = new ArrayList<>(schema.headers().size());
                    for (int column = 1; column <= schema.headers().size(); column++) {
                        row.add(resultSet.getString(column));
                    }
                    rows.add(Collections.unmodifiableList(row));
                }
                return List.copyOf(rows);
            }
        } catch (Exception e) {
            throw new IllegalStateException("分页读取 Doris 预检导出失败: " + safe(e), e);
        }
    }

    @Override
    public List<DorisPrecheckExportService.ExportFile> exportOutfile(
            Long runId,
            DfetlPrecheckIssueQuery query,
            ExportSchema schema,
            OutfileRequest request) {
        validateOutfile(request);
        QueryContext context = context(runId);
        List<Object> parameters = new ArrayList<>();
        String predicate = predicate(runId, query, parameters, "i.");
        StringBuilder sql = new StringBuilder(selectSql(context, schema.rawColumns(), predicate))
                .append(" ORDER BY i.row_id,i.field_code,i.error_type")
                .append(" INTO OUTFILE ").append(stringLiteral(request.uriPrefix()))
                .append(" FORMAT AS CSV");
        if (!request.properties().isEmpty()) {
            sql.append(" PROPERTIES(");
            int propertyIndex = 0;
            for (Map.Entry<String, String> property : request.properties().entrySet()) {
                if (propertyIndex++ > 0) {
                    sql.append(',');
                }
                sql.append(stringLiteral(property.getKey())).append('=')
                        .append(stringLiteral(property.getValue()));
            }
            sql.append(')');
        }
        try (Connection connection = openConnection(context.target());
             PreparedStatement statement = connection.prepareStatement(sql.toString())) {
            bind(statement, parameters);
            try (ResultSet resultSet = statement.executeQuery()) {
                List<DorisPrecheckExportService.ExportFile> files = new ArrayList<>();
                ResultSetMetaData metadata = resultSet.getMetaData();
                Map<String, Integer> labels = labels(metadata);
                while (resultSet.next()) {
                    String path = firstString(resultSet, labels,
                            "url", "fileurl", "file_url", "file");
                    if (path == null || path.isBlank()) {
                        throw new IllegalStateException("Doris OUTFILE 未返回文件地址");
                    }
                    Long rows = firstLong(resultSet, labels, "totalrows", "total_rows", "rows");
                    Long bytes = firstLong(resultSet, labels, "filesize", "file_size", "bytes");
                    String name = path.substring(path.lastIndexOf('/') + 1);
                    files.add(new DorisPrecheckExportService.ExportFile(
                            "OUTFILE", path, name, "text/csv", rows, bytes));
                }
                if (files.isEmpty()) {
                    throw new IllegalStateException("Doris OUTFILE 未返回导出文件");
                }
                return List.copyOf(files);
            }
        } catch (Exception e) {
            // SQL 中可能包含对象存储凭证，禁止把驱动回显的 SQL/异常明文写入元库。
            throw new IllegalStateException("Doris OUTFILE 导出失败", e);
        }
    }

    private QueryContext context(Long runId) {
        if (runId == null || runId <= 0) {
            throw new IllegalArgumentException("runId 必须为正整数");
        }
        DfetlPrecheckRun run = runRepository.findById(runId)
                .orElseThrow(() -> new NoSuchElementException("数据预检运行不存在: " + runId));
        InstitutionDatasetRoute route = routeRepository.findById(run.getRouteId())
                .orElseThrow(() -> new NoSuchElementException("机构数据集路由不存在: " + run.getRouteId()));
        TargetDataSource target = targetRepository.findById(route.getTargetDatasourceId())
                .orElseThrow(() -> new NoSuchElementException(
                        "目标数据源不存在: " + route.getTargetDatasourceId()));
        if (!"NORMAL".equals(target.getStatus())) {
            throw new IllegalStateException("目标数据源不可用于预检导出: " + target.getStatus());
        }
        String staging = run.getStagingTable();
        int separator = staging == null ? -1 : staging.indexOf('.');
        if (separator <= 0 || separator == staging.length() - 1) {
            throw new IllegalStateException("预检运行缺少 Doris 暂存表: runId=" + runId);
        }
        String database = IdentifierSanitizer.requireValid(
                staging.substring(0, separator), "precheckDatabase");
        String rawTable = IdentifierSanitizer.requireValid(
                staging.substring(separator + 1), "precheckRawTable");
        String expectedRawTable = DorisPrecheckTableSpec.rawTableForDatasetCode(
                route.getTargetTable());
        if (!expectedRawTable.equals(rawTable)) {
            throw new IllegalStateException("预检暂存表与数据集不匹配: runId=" + runId);
        }
        return new QueryContext(target, database, rawTable);
    }

    private String selectSql(QueryContext context, List<String> rawColumns, String predicate) {
        StringBuilder select = new StringBuilder("SELECT ");
        for (int index = 0; index < ISSUE_COLUMNS.size(); index++) {
            if (index > 0) {
                select.append(',');
            }
            select.append("i.").append(ISSUE_COLUMNS.get(index));
        }
        for (String rawColumn : rawColumns) {
            select.append(",r.").append(quoted(rawColumn));
        }
        return select.append(" FROM ").append(qualified(context.database(), "precheck_issue"))
                .append(" i LEFT JOIN ").append(qualified(context.database(), context.rawTable()))
                .append(" r ON r.run_id=i.run_id AND r.row_id=i.row_id WHERE ")
                .append(predicate).toString();
    }

    private String predicate(
            Long runId,
            DfetlPrecheckIssueQuery query,
            List<Object> parameters,
            String alias) {
        DfetlPrecheckIssueQuery effective = query == null
                ? new DfetlPrecheckIssueQuery(null, null, null, null, 0, 50) : query;
        StringBuilder sql = new StringBuilder(alias).append("run_id=?");
        parameters.add(runId);
        appendEquals(sql, parameters, alias + "field_code", effective.fieldCode());
        appendEquals(sql, parameters, alias + "error_type", effective.errorType());
        appendEquals(sql, parameters, alias + "severity", effective.severity());
        if (effective.businessPk() != null) {
            sql.append(" AND ").append(alias).append("business_pk LIKE ? ESCAPE '\\\\'");
            parameters.add("%" + escapeLike(effective.businessPk()) + "%");
        }
        return sql.toString();
    }

    private void appendEquals(
            StringBuilder sql, List<Object> parameters, String column, String value) {
        if (value != null) {
            sql.append(" AND ").append(column).append("=?");
            parameters.add(value);
        }
    }

    private int bind(PreparedStatement statement, List<Object> parameters) throws Exception {
        int index = 1;
        for (Object parameter : parameters) {
            statement.setObject(index++, parameter);
        }
        return index;
    }

    private Connection openConnection(TargetDataSource target) throws Exception {
        int port = target.getFePort() == null ? 9030 : target.getFePort();
        String url = "jdbc:mysql://" + target.getFeHost() + ":" + port + "/" + target.getDbName()
                + "?useSSL=false&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true";
        return connectionPoolManager.getConnection(
                url, target.getUsername(), aesUtil.decrypt(target.getPasswordEnc()));
    }

    private void validateOutfile(OutfileRequest request) {
        URI uri = URI.create(request.uriPrefix());
        if (uri.getScheme() == null
                || (!"s3".equalsIgnoreCase(uri.getScheme())
                && !"hdfs".equalsIgnoreCase(uri.getScheme()))) {
            throw new IllegalArgumentException("OUTFILE URI 只支持 s3:// 或 hdfs://");
        }
        request.properties().keySet().forEach(key -> {
            if (!PROPERTY_KEY.matcher(key).matches()) {
                throw new IllegalArgumentException("OUTFILE property key 非法");
            }
        });
    }

    private Map<String, Integer> labels(ResultSetMetaData metadata) throws Exception {
        Map<String, Integer> result = new LinkedHashMap<>();
        for (int index = 1; index <= metadata.getColumnCount(); index++) {
            result.put(metadata.getColumnLabel(index).toLowerCase(Locale.ROOT), index);
        }
        return result;
    }

    private String firstString(ResultSet resultSet, Map<String, Integer> labels, String... names)
            throws Exception {
        for (String name : names) {
            Integer index = labels.get(name);
            if (index != null) {
                return resultSet.getString(index);
            }
        }
        return null;
    }

    private Long firstLong(ResultSet resultSet, Map<String, Integer> labels, String... names)
            throws Exception {
        for (String name : names) {
            Integer index = labels.get(name);
            if (index != null) {
                long value = resultSet.getLong(index);
                return resultSet.wasNull() ? null : value;
            }
        }
        return null;
    }

    private static String qualified(String database, String table) {
        return quoted(database) + "." + quoted(table);
    }

    private static String quoted(String identifier) {
        return "`" + IdentifierSanitizer.requireValid(identifier, "identifier") + "`";
    }

    private static String stringLiteral(String value) {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private static String escapeLike(String value) {
        return value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    private static String safe(Exception e) {
        String detail = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        return ExecutionErrorSanitizer.sanitize(detail);
    }

    private record QueryContext(TargetDataSource target, String database, String rawTable) {
    }
}
