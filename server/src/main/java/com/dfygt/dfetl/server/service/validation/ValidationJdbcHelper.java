package com.dfygt.dfetl.server.service.validation;

import com.dfygt.dfetl.server.entity.SourceDataSource;
import com.dfygt.dfetl.server.entity.SyncTask;
import com.dfygt.dfetl.server.repository.TargetDataSourceRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 校验 JDBC 连接辅助类。
 *
 * <p>从 {@link com.dfygt.dfetl.server.service.ValidationRunner} 提取的 JDBC URL 构建和脱敏逻辑，
 * 包含 buildSourceJdbcUrl、buildDorisJdbcUrl、sanitizeJdbcUrlForLog 方法，
 * 以及新增的 sanitizeSqlForPersistence 和 truncateSql 工具方法。
 */
@Component
@RequiredArgsConstructor
public class ValidationJdbcHelper {

    private final TargetDataSourceRepository targetDsRepo;

    /**
     * 构建源端 JDBC URL（支持 MySQL/PostgreSQL/Oracle/SQLServer/Doris）。
     */
    public String buildSourceJdbcUrl(SourceDataSource ds) {
        String dbType = ds.getType().toUpperCase();
        return switch (dbType) {
            case "MYSQL" -> String.format(
                    "jdbc:mysql://%s:%d/%s?useSSL=false&connectTimeout=5000&socketTimeout=30000",
                    ds.getHost(), ds.getPort(), ds.getDbName());
            case "POSTGRESQL" -> String.format(
                    "jdbc:postgresql://%s:%d/%s?connectTimeout=5",
                    ds.getHost(), ds.getPort(), ds.getDbName());
            case "ORACLE" -> String.format(
                    "jdbc:oracle:thin:@%s:%d/%s",
                    ds.getHost(), ds.getPort(), ds.getDbName());
            case "SQLSERVER" -> String.format(
                    "jdbc:sqlserver://%s:%d;databaseName=%s;loginTimeout=5",
                    ds.getHost(), ds.getPort(), ds.getDbName());
            case "DORIS" -> String.format(
                    "jdbc:mysql://%s:%d/%s?useSSL=false&allowPublicKeyRetrieval=true&connectTimeout=5000&socketTimeout=30000",
                    ds.getHost(), ds.getPort(), ds.getDbName());
            default -> throw new IllegalArgumentException("Unsupported DB type: " + dbType);
        };
    }

    /**
     * 构建 Doris 目标端 JDBC URL。
     */
    public String buildDorisJdbcUrl(SyncTask task) {
        if (task.getTargetDataSourceId() == null) {
            return "jdbc:mysql://127.0.0.1:9030/";
        }
        var tds = targetDsRepo.findById(task.getTargetDataSourceId()).orElse(null);
        if (tds == null) {
            return "jdbc:mysql://127.0.0.1:9030/";
        }
        // Doris 通过 MySQL 协议，连 FE 的 query_port (默认 9030)
        return String.format(
                "jdbc:mysql://%s:%d/%s?useSSL=false&connectTimeout=5000",
                tds.getFeHost(), tds.getFePort(), tds.getDbName());
    }

    /**
     * JDBC URL 脱敏：隐藏 host/port/db 和 password 参数。
     */
    public static String sanitizeJdbcUrlForLog(String jdbcUrl) {
        if (jdbcUrl == null || jdbcUrl.isBlank()) {
            return "";
        }
        java.util.regex.Matcher matcher = java.util.regex.Pattern
                .compile("^(jdbc:[a-zA-Z0-9]+:)//[^/:?;]+(?::\\d+)?/[^?;]+.*$")
                .matcher(jdbcUrl);
        if (matcher.matches()) {
            return matcher.group(1) + "//<redacted-host>:<redacted-port>/<redacted-db>";
        }
        int queryIdx = jdbcUrl.indexOf('?');
        String withoutQuery = queryIdx >= 0 ? jdbcUrl.substring(0, queryIdx) : jdbcUrl;
        return withoutQuery.replaceAll("(?i)(password|pwd)=([^;]+)", "$1=<redacted>");
    }

    /**
     * SQL 字符串中可能出现的 JDBC URL 片段脱敏（password/pwd 参数值替换为 {@code <redacted>}）。
     */
    public static String sanitizeSqlForPersistence(String sql) {
        if (sql == null) return null;
        return sql.replaceAll("(?i)(password|pwd)=([^;&\\s]+)", "$1=<redacted>");
    }

    /**
     * SQL 截断：超过 maxLength 时截断并追加 {@code ...[TRUNCATED]} 标记。
     */
    public static String truncateSql(String sql, int maxLength) {
        if (sql == null || sql.length() <= maxLength) return sql;
        return sql.substring(0, maxLength) + "...[TRUNCATED]";
    }
}
