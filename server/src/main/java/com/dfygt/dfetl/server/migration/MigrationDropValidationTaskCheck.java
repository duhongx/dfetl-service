package com.dfygt.dfetl.server.migration;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * 迁移安全校验工具类：在执行 DROP TABLE validation_task 前验证数据完整性。
 * <p>
 * 校验逻辑：
 * <ol>
 *   <li>若 validation_task 表已不存在（已删除），直接通过</li>
 *   <li>检查 validation_run 中 status IS NOT NULL 的记录数 ≥ validation_task 总数</li>
 *   <li>校验失败时输出错误提示并以非零退出码中止</li>
 * </ol>
 * <p>
 * 使用方式（独立运行）：
 * <pre>
 *   java -cp server.jar com.dfygt.dfetl.server.migration.MigrationDropValidationTaskCheck \
 *       jdbc:postgresql://host:5432/db username password
 * </pre>
 * <p>
 * 也可在 Spring Boot 环境中通过注入 DataSource 调用 {@link #check(Connection)} 方法。
 *
 * @see <a href="../../resources/db/migration_drop_validation_task.sql">migration_drop_validation_task.sql</a>
 */
public class MigrationDropValidationTaskCheck {

    private MigrationDropValidationTaskCheck() {
        // 工具类，禁止实例化
    }

    /**
     * 执行安全校验。
     *
     * @param conn 数据库连接
     * @return 校验结果
     * @throws SQLException 数据库访问异常
     */
    public static CheckResult check(Connection conn) throws SQLException {
        // 1. 检查 validation_task 表是否存在
        if (!tableExists(conn, "validation_task")) {
            return CheckResult.success("validation_task 表已不存在，无需删除，校验通过。");
        }

        // 2. 统计 validation_task 总记录数
        long taskCount = countAll(conn, "SELECT COUNT(*) FROM validation_task");

        // 3. 统计 validation_run 中已迁移数据（status IS NOT NULL）
        long runMigratedCount = countAll(conn,
                "SELECT COUNT(*) FROM validation_run WHERE status IS NOT NULL");

        // 4. 安全校验：validation_run 已迁移记录数 ≥ validation_task 总数
        if (runMigratedCount >= taskCount) {
            return CheckResult.success(
                    "安全校验通过：validation_run 已迁移记录数（%d）≥ validation_task 总数（%d），可以安全删表。"
                            .formatted(runMigratedCount, taskCount));
        } else {
            return CheckResult.failure(
                    "安全校验失败：validation_run 已迁移记录数（%d）< validation_task 总数（%d）。"
                            .formatted(runMigratedCount, taskCount)
                            + "\n数据迁移不完整，请先执行 migration_copy_data_from_validation_task.sql 完成数据迁移。"
                            + "\n中止 DROP TABLE 操作。");
        }
    }

    /**
     * 检查指定表是否存在于当前数据库。
     */
    private static boolean tableExists(Connection conn, String tableName) throws SQLException {
        // 使用 information_schema 查询，兼容 PostgreSQL 和 MySQL
        String sql = "SELECT COUNT(*) FROM information_schema.tables "
                + "WHERE table_name = ? AND table_schema = current_schema()";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, tableName);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() && rs.getLong(1) > 0;
            }
        }
    }

    /**
     * 执行 COUNT 查询并返回结果。
     */
    private static long countAll(Connection conn, String sql) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            return rs.next() ? rs.getLong(1) : 0L;
        }
    }

    // ── 命令行入口 ──────────────────────────────────────────────────────────

    /**
     * 命令行入口，用于独立执行安全校验。
     * <p>
     * 参数：jdbcUrl username password
     * <p>
     * 退出码：0 = 校验通过，1 = 校验失败，2 = 执行异常
     */
    public static void main(String[] args) {
        if (args.length < 3) {
            System.err.println("用法: MigrationDropValidationTaskCheck <jdbcUrl> <username> <password>");
            System.err.println("示例: MigrationDropValidationTaskCheck jdbc:postgresql://localhost:5432/df_ygt df_etl password");
            System.exit(2);
        }

        String jdbcUrl = args[0];
        String username = args[1];
        String password = args[2];

        System.out.println("═══════════════════════════════════════════════════════════════");
        System.out.println("  迁移安全校验：DROP TABLE validation_task 前置检查");
        System.out.println("═══════════════════════════════════════════════════════════════");
        System.out.println("连接: " + sanitizeUrl(jdbcUrl));

        try (Connection conn = DriverManager.getConnection(jdbcUrl, username, password)) {
            CheckResult result = check(conn);
            System.out.println();
            System.out.println(result.passed() ? "✅ " + result.message() : "❌ " + result.message());
            System.out.println("═══════════════════════════════════════════════════════════════");
            System.exit(result.passed() ? 0 : 1);
        } catch (SQLException e) {
            System.err.println("❌ 数据库连接或查询失败: " + e.getMessage());
            System.exit(2);
        }
    }

    /**
     * 脱敏 JDBC URL（隐藏密码参数）。
     */
    private static String sanitizeUrl(String url) {
        if (url == null) return "(null)";
        return url.replaceAll("password=[^&]*", "password=***");
    }

    // ── 校验结果 ────────────────────────────────────────────────────────────

    /**
     * 校验结果记录。
     *
     * @param passed  是否通过
     * @param message 结果描述
     */
    public record CheckResult(boolean passed, String message) {

        public static CheckResult success(String message) {
            return new CheckResult(true, message);
        }

        public static CheckResult failure(String message) {
            return new CheckResult(false, message);
        }
    }
}
