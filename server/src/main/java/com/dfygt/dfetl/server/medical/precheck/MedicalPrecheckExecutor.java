package com.dfygt.dfetl.server.medical.precheck;

import com.dfygt.dfetl.server.service.SourceDataSourceService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.Statement;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.function.UnaryOperator;

/** 执行预检计划中的 SQL；任何执行异常或 BLOCKER 命中都 fail-closed。 */
@Service
@RequiredArgsConstructor
public class MedicalPrecheckExecutor {

    private static final int SNAPSHOT_TIMEOUT_SECONDS = 600;
    private static final String POSTGRESQL = "POSTGRESQL";

    private final SourceDataSourceService sourceDataSourceService;

    public void assertNoBlockers(Long sourceDataSourceId, List<MedicalPrecheckCheck> checks) {
        if (checks == null || checks.isEmpty()) {
            return;
        }
        try (var connection = sourceDataSourceService.openConnection(sourceDataSourceId)) {
            executeChecks(connection, checks, UnaryOperator.identity());
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw executionFailed(e);
        }
    }

    /**
     * 运行门禁入口。PostgreSQL 在同一 JDBC 会话内只物化一次源对象临时快照，
     * 之后仍执行 MedicalPrecheckService 生成的原检查 SQL，仅替换源关系引用。
     * 其它方言保持原逐项执行行为。
     */
    public void assertNoBlockers(
            Long sourceDataSourceId,
            String sourceSchema,
            String sourceObject,
            List<MedicalPrecheckCheck> checks) {
        if (checks == null || checks.isEmpty()) {
            return;
        }
        try {
            String sourceType = sourceDataSourceService.findById(sourceDataSourceId).getType();
            if (!POSTGRESQL.equalsIgnoreCase(sourceType)) {
                assertNoBlockers(sourceDataSourceId, checks);
                return;
            }
            assertNoPostgreSqlBlockersFromSnapshot(
                    sourceDataSourceId, sourceSchema, sourceObject, checks);
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw executionFailed(e);
        }
    }

    private void assertNoPostgreSqlBlockersFromSnapshot(
            Long sourceDataSourceId,
            String sourceSchema,
            String sourceObject,
            List<MedicalPrecheckCheck> checks) {
        String qualifiedSource = qualifyPostgreSqlSource(sourceSchema, sourceObject);
        String tempName = "dfetl_medical_precheck_"
                + UUID.randomUUID().toString().replace("-", "");
        String quotedTemp = quotePostgreSqlIdentifier(tempName);
        String qualifiedTemp = "pg_temp." + quotePostgreSqlIdentifier(tempName);
        try (var connection = sourceDataSourceService.openConnection(sourceDataSourceId)) {
            boolean originalAutoCommit = connection.getAutoCommit();
            boolean tempCreated = false;
            Exception failure = null;
            try {
                if (!originalAutoCommit) {
                    connection.setAutoCommit(true);
                }
                try (Statement statement = connection.createStatement()) {
                    statement.setQueryTimeout(SNAPSHOT_TIMEOUT_SECONDS);
                    statement.executeUpdate(
                            "CREATE TEMP TABLE " + quotedTemp
                                    + " AS SELECT * FROM " + qualifiedSource
                                    + " WITH NO DATA");
                }
                tempCreated = true;

                connection.setAutoCommit(false);
                try (Statement statement = connection.createStatement()) {
                    statement.execute("SET TRANSACTION READ ONLY");
                }
                try (Statement statement = connection.createStatement()) {
                    statement.setQueryTimeout(SNAPSHOT_TIMEOUT_SECONDS);
                    statement.executeUpdate(
                            "INSERT INTO " + quotedTemp + " SELECT * FROM " + qualifiedSource);
                }
                executeChecks(connection, checks,
                        sql -> replaceSourceWithSnapshot(sql, qualifiedSource, qualifiedTemp));
                connection.commit();
            } catch (Exception e) {
                try {
                    connection.rollback();
                } catch (Exception rollbackFailure) {
                    e.addSuppressed(rollbackFailure);
                }
                failure = e;
            } finally {
                try {
                    connection.setAutoCommit(true);
                    if (tempCreated) {
                        try (Statement statement = connection.createStatement()) {
                            statement.executeUpdate("DROP TABLE IF EXISTS " + quotedTemp);
                        }
                    }
                    if (!originalAutoCommit) {
                        connection.setAutoCommit(false);
                    }
                } catch (Exception cleanupFailure) {
                    if (failure == null) {
                        failure = cleanupFailure;
                    } else {
                        failure.addSuppressed(cleanupFailure);
                    }
                }
            }
            if (failure != null) {
                throw failure;
            }
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw executionFailed(e);
        }
    }

    private void executeChecks(
            java.sql.Connection connection,
            List<MedicalPrecheckCheck> checks,
            UnaryOperator<String> sqlRewriter) throws Exception {
        for (MedicalPrecheckCheck check : checks) {
            if (check == null || check.severity() != MedicalPrecheckSeverity.BLOCKER) {
                continue;
            }
            String sql = sqlRewriter.apply(check.sql());
            try (Statement statement = connection.createStatement()) {
                statement.setQueryTimeout(Math.max(1, check.timeoutSeconds()));
                statement.setMaxRows(1);
                try (ResultSet rs = statement.executeQuery(sql)) {
                    if (hasViolation(rs)) {
                        throw blocked(check);
                    }
                }
            }
        }
    }

    private String replaceSourceWithSnapshot(
            String sql,
            String qualifiedSource,
            String qualifiedTemp) {
        if (sql == null || !sql.contains(qualifiedSource)) {
            throw new IllegalStateException(
                    "PRECHECK_FAILED: 医共体动态预检 SQL 未引用当前源对象，"
                            + "已禁止医共体任务执行");
        }
        return sql.replace(qualifiedSource, qualifiedTemp);
    }

    private String qualifyPostgreSqlSource(String schema, String sourceObject) {
        String quotedObject = quotePostgreSqlIdentifier(sourceObject);
        if (schema == null || schema.isBlank()) {
            return quotedObject;
        }
        return quotePostgreSqlIdentifier(schema) + "." + quotedObject;
    }

    private String quotePostgreSqlIdentifier(String identifier) {
        if (identifier == null || identifier.isBlank()) {
            throw new IllegalStateException(
                    "PRECHECK_FAILED: 医共体动态预检源对象为空，"
                            + "已禁止医共体任务执行");
        }
        return "\"" + identifier.replace("\"", "\"\"") + "\"";
    }

    private boolean hasViolation(ResultSet rs) throws Exception {
        if (!rs.next()) {
            return false;
        }
        ResultSetMetaData meta = rs.getMetaData();
        String label = meta == null ? null : meta.getColumnLabel(1);
        if (label != null && "invalid_count".equals(label.trim().toLowerCase(Locale.ROOT))) {
            return rs.getLong(1) > 0L;
        }
        // sample/duplicate 查询只要返回一行即表示命中，不读取原始值，避免敏感数据进入异常。
        return true;
    }

    private IllegalStateException blocked(MedicalPrecheckCheck check) {
        return new IllegalStateException(
                "PRECHECK_BLOCKED: code=" + check.code()
                        + ", field=" + check.field()
                        + ", message=" + check.message());
    }

    private String safeMessage(Exception e) {
        String message = e.getMessage();
        return message == null || message.isBlank() ? e.getClass().getSimpleName() : message;
    }

    private IllegalStateException executionFailed(Exception e) {
        return new IllegalStateException(
                "PRECHECK_FAILED: 医共体动态预检执行失败，已禁止医共体任务执行: "
                        + safeMessage(e),
                e);
    }
}
