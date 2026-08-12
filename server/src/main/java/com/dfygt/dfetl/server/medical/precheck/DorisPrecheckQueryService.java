package com.dfygt.dfetl.server.medical.precheck;

import com.dfygt.dfetl.server.common.AesUtil;
import com.dfygt.dfetl.server.common.IdentifierSanitizer;
import com.dfygt.dfetl.server.common.JdbcConnectionPoolManager;
import com.dfygt.dfetl.server.common.ExecutionErrorSanitizer;
import com.dfygt.dfetl.server.dto.DfetlPrecheckIssueDto;
import com.dfygt.dfetl.server.dto.DfetlPrecheckIssuePageDto;
import com.dfygt.dfetl.server.dto.DfetlPrecheckIssueQuery;
import com.dfygt.dfetl.server.dto.DfetlPrecheckSummaryDto;
import com.dfygt.dfetl.server.entity.DfetlPrecheckRun;
import com.dfygt.dfetl.server.entity.Institution;
import com.dfygt.dfetl.server.entity.InstitutionDatasetRoute;
import com.dfygt.dfetl.server.entity.TargetDataSource;
import com.dfygt.dfetl.server.external.security.ExternalApiAuthorizationService;
import com.dfygt.dfetl.server.repository.DfetlPrecheckRunRepository;
import com.dfygt.dfetl.server.repository.InstitutionDatasetRouteRepository;
import com.dfygt.dfetl.server.repository.InstitutionRepository;
import com.dfygt.dfetl.server.repository.TargetDataSourceRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;

/** 分页读取 Doris 预检汇总和问题明细，不把整批问题装入 JVM。 */
@Service
@RequiredArgsConstructor
public class DorisPrecheckQueryService {

    private final DfetlPrecheckRunRepository runRepository;
    private final InstitutionDatasetRouteRepository routeRepository;
    private final InstitutionRepository institutionRepository;
    private final TargetDataSourceRepository targetRepository;
    private final ExternalApiAuthorizationService authorizationService;
    private final AesUtil aesUtil;
    private final JdbcConnectionPoolManager connectionPoolManager;

    public List<DfetlPrecheckSummaryDto> summaries(Long runId) {
        QueryContext context = context(runId);
        String sql = "SELECT field_code,error_type,severity,issue_count,affected_rows FROM "
                + qualified(context.database(), "precheck_summary")
                + " WHERE run_id=? ORDER BY field_code,error_type,severity";
        try (Connection connection = openConnection(context.target());
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, runId);
            try (ResultSet resultSet = statement.executeQuery()) {
                List<DfetlPrecheckSummaryDto> result = new ArrayList<>();
                while (resultSet.next()) {
                    result.add(new DfetlPrecheckSummaryDto(
                            resultSet.getString("field_code"),
                            resultSet.getString("error_type"),
                            resultSet.getString("severity"),
                            resultSet.getLong("issue_count"),
                            resultSet.getLong("affected_rows")));
                }
                return List.copyOf(result);
            }
        } catch (Exception e) {
            throw new IllegalStateException("读取 Doris 预检汇总失败: " + safe(e), e);
        }
    }

    public DfetlPrecheckIssuePageDto issues(Long runId, DfetlPrecheckIssueQuery query) {
        if (query == null) {
            query = new DfetlPrecheckIssueQuery(null, null, null, null, 0, 50);
        }
        QueryContext context = context(runId);
        List<Object> parameters = new ArrayList<>();
        String predicate = predicate(runId, query, parameters);
        String table = qualified(context.database(), "precheck_issue");
        String countSql = "SELECT COUNT(*) FROM " + table + " WHERE " + predicate;
        String selectSql = "SELECT row_id,row_hash,business_pk,field_code,error_type,severity,"
                + "raw_value,normalized_value,standard_rule,error_message FROM " + table
                + " WHERE " + predicate
                + " ORDER BY row_id,field_code,error_type LIMIT ? OFFSET ?";
        try (Connection connection = openConnection(context.target())) {
            long total = count(connection, countSql, parameters);
            List<DfetlPrecheckIssueDto> content = select(
                    connection, selectSql, parameters, query.size(), (long) query.page() * query.size());
            int totalPages = total == 0 ? 0 : (int) ((total + query.size() - 1) / query.size());
            return new DfetlPrecheckIssuePageDto(
                    content, query.page(), query.size(), total, totalPages);
        } catch (Exception e) {
            throw new IllegalStateException("读取 Doris 预检问题失败: " + safe(e), e);
        }
    }

    private long count(Connection connection, String sql, List<Object> parameters) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            bind(statement, parameters);
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    throw new IllegalStateException("Doris 未返回预检问题总数");
                }
                return resultSet.getLong(1);
            }
        }
    }

    private List<DfetlPrecheckIssueDto> select(
            Connection connection,
            String sql,
            List<Object> parameters,
            int limit,
            long offset) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            int index = bind(statement, parameters);
            statement.setInt(index++, limit);
            statement.setLong(index, offset);
            try (ResultSet resultSet = statement.executeQuery()) {
                List<DfetlPrecheckIssueDto> result = new ArrayList<>();
                while (resultSet.next()) {
                    result.add(new DfetlPrecheckIssueDto(
                            resultSet.getString("row_id"),
                            resultSet.getString("row_hash"),
                            resultSet.getString("business_pk"),
                            resultSet.getString("field_code"),
                            resultSet.getString("error_type"),
                            resultSet.getString("severity"),
                            resultSet.getString("raw_value"),
                            resultSet.getString("normalized_value"),
                            resultSet.getString("standard_rule"),
                            resultSet.getString("error_message")));
                }
                return List.copyOf(result);
            }
        }
    }

    private String predicate(
            Long runId,
            DfetlPrecheckIssueQuery query,
            List<Object> parameters) {
        StringBuilder sql = new StringBuilder("run_id=?");
        parameters.add(runId);
        appendEquals(sql, parameters, "field_code", query.fieldCode());
        appendEquals(sql, parameters, "error_type", query.errorType());
        appendEquals(sql, parameters, "severity", query.severity());
        if (query.businessPk() != null) {
            sql.append(" AND business_pk LIKE ?");
            parameters.add("%" + escapeLike(query.businessPk()) + "%");
            sql.append(" ESCAPE '\\\\'");
        }
        return sql.toString();
    }

    private void appendEquals(
            StringBuilder sql,
            List<Object> parameters,
            String column,
            String value) {
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

    private QueryContext context(Long runId) {
        if (runId == null || runId <= 0) {
            throw new IllegalArgumentException("runId 必须为正整数");
        }
        DfetlPrecheckRun run = runRepository.findById(runId)
                .orElseThrow(() -> new NoSuchElementException("数据预检运行不存在: " + runId));
        InstitutionDatasetRoute route = routeRepository.findById(run.getRouteId())
                .orElseThrow(() -> new NoSuchElementException("机构数据集路由不存在: " + run.getRouteId()));
        Institution institution = institutionRepository.findById(run.getInstitutionId())
                .orElseThrow(() -> new NoSuchElementException("机构不存在: " + run.getInstitutionId()));
        authorizationService.assertAllowed(institution.getCode());
        TargetDataSource target = targetRepository.findById(route.getTargetDatasourceId())
                .orElseThrow(() -> new NoSuchElementException(
                        "目标数据源不存在: " + route.getTargetDatasourceId()));
        if (!"NORMAL".equals(target.getStatus())) {
            throw new IllegalStateException("目标数据源不可用于预检查询: " + target.getStatus());
        }
        String staging = run.getStagingTable();
        int separator = staging == null ? -1 : staging.indexOf('.');
        if (separator <= 0) {
            throw new IllegalStateException("预检运行缺少 Doris 暂存表: runId=" + runId);
        }
        String database = IdentifierSanitizer.requireValid(
                staging.substring(0, separator), "precheckDatabase");
        return new QueryContext(target, database);
    }

    private Connection openConnection(TargetDataSource target) throws Exception {
        int port = target.getFePort() == null ? 9030 : target.getFePort();
        String url = "jdbc:mysql://" + target.getFeHost() + ":" + port + "/" + target.getDbName()
                + "?useSSL=false&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true";
        return connectionPoolManager.getConnection(
                url, target.getUsername(), aesUtil.decrypt(target.getPasswordEnc()));
    }

    private static String qualified(String database, String table) {
        return "`" + database + "`.`" + table + "`";
    }

    private static String escapeLike(String value) {
        return value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    private static String safe(Exception e) {
        String detail = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        return ExecutionErrorSanitizer.sanitize(detail);
    }

    private record QueryContext(TargetDataSource target, String database) {
    }
}
