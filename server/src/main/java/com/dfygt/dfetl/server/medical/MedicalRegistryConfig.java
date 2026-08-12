package com.dfygt.dfetl.server.medical;

import com.dfygt.dfetl.server.common.AesUtil;
import com.dfygt.dfetl.server.entity.SystemSetting;
import com.dfygt.dfetl.server.repository.SystemSettingRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * 医共体规范库全局配置服务。
 * <p>
 * 从 system_setting 表读取 medical.registry.* 配置项，
 * 提供 Doris 连接信息和规范表名配置。
 * </p>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class MedicalRegistryConfig {

    private static final String KEY_ENABLED = "medical.registry.enabled";
    private static final String KEY_DORIS_HOST = "medical.registry.doris.host";
    private static final String KEY_DORIS_PORT = "medical.registry.doris.port";
    private static final String KEY_DORIS_USERNAME = "medical.registry.doris.username";
    private static final String KEY_DORIS_PASSWORD = "medical.registry.doris.password";
    private static final String KEY_DORIS_DATABASE = "medical.registry.doris.database";
    private static final String KEY_DATASET_TABLE = "medical.registry.dataset_table";
    private static final String KEY_FIELD_TABLE = "medical.registry.field_table";
    private static final String KEY_DATA_ITEM_TABLE = "medical.registry.data_item_table";
    public static final String STANDARD_DATASET_PREFIX = "ODS_YL_";

    private final SystemSettingRepository settingRepository;
    private final AesUtil aesUtil;

    // ── 配置读取方法 ──

    public boolean isEnabled() {
        return "true".equalsIgnoreCase(getValue(KEY_ENABLED).orElse("false"));
    }

    public String getDorisHost() {
        return getValue(KEY_DORIS_HOST).orElse("");
    }

    public int getDorisPort() {
        return Integer.parseInt(getValue(KEY_DORIS_PORT).orElse("9030"));
    }

    public String getUsername() {
        return getValue(KEY_DORIS_USERNAME).orElse("");
    }

    /**
     * 获取解密后的密码。
     * 如果密码为空或解密失败，返回空字符串。
     */
    public String getPassword() {
        String encrypted = getValue(KEY_DORIS_PASSWORD).orElse("");
        if (encrypted.isBlank()) return "";
        try {
            return aesUtil.decrypt(encrypted);
        } catch (Exception e) {
            log.warn("[MedicalRegistryConfig] 密码解密失败: {}", e.getMessage());
            return "";
        }
    }

    public String getDatabase() {
        return getValue(KEY_DORIS_DATABASE).orElse("df_ygt");
    }

    public String getDatasetTable() {
        return getValue(KEY_DATASET_TABLE).orElse("dm_shujuji");
    }

    public String getFieldTable() {
        return getValue(KEY_FIELD_TABLE).orElse("dm_shujujzd");
    }

    public String getDataItemTable() {
        return getValue(KEY_DATA_ITEM_TABLE).orElse("dm_shujuxiang");
    }

    public String getDatasetPrefix() {
        return STANDARD_DATASET_PREFIX;
    }

    /**
     * 判断全局配置是否已完整设置（host/port/username/password/database 均非空）。
     */
    public boolean isConfigured() {
        return !getDorisHost().isBlank()
                && !getUsername().isBlank()
                && !getValue(KEY_DORIS_PASSWORD).orElse("").isBlank()
                && !getDatabase().isBlank();
    }

    // ── 连接方法 ──

    /**
     * 使用全局配置打开 Doris JDBC 连接。
     *
     * @return JDBC Connection（调用方负责关闭）
     * @throws SQLException 连接失败
     */
    public Connection openConnection() throws SQLException {
        String jdbcUrl = buildJdbcUrl(getDorisHost(), getDorisPort(), getDatabase());
        return DriverManager.getConnection(jdbcUrl, getUsername(), getPassword());
    }

    /**
     * 使用指定参数打开 Doris JDBC 连接（用于测试连接场景）。
     *
     * @param host     Doris FE 主机
     * @param port     Doris FE 端口
     * @param database 数据库名
     * @param username 用户名
     * @param password 明文密码
     * @return JDBC Connection（调用方负责关闭）
     * @throws SQLException 连接失败
     */
    public Connection openConnection(String host, int port, String database, String username, String password)
            throws SQLException {
        String jdbcUrl = buildJdbcUrl(host, port, database);
        return DriverManager.getConnection(jdbcUrl, username, password);
    }

    /**
     * 测试全局配置的连接可用性。
     *
     * @return 测试结果 Map，包含 success、message、datasetCount
     */
    public Map<String, Object> testConnection() {
        return testConnection(getDorisHost(), getDorisPort(), getDatabase(),
                getUsername(), getPassword(), getDatasetTable(), getDatasetPrefix());
    }

    /**
     * 测试指定参数的连接可用性。
     *
     * @param host         Doris FE 主机
     * @param port         Doris FE 端口
     * @param database     数据库名
     * @param username     用户名
     * @param password     明文密码
     * @param datasetTable 数据集表名
     * @return 测试结果 Map，包含 success、message、datasetCount
     */
    public Map<String, Object> testConnection(String host, int port, String database,
                                              String username, String password, String datasetTable) {
        return testConnection(host, port, database, username, password, datasetTable, STANDARD_DATASET_PREFIX);
    }

    public Map<String, Object> testConnection(String host, int port, String database,
                                              String username, String password, String datasetTable,
                                              String datasetPrefix) {
        long start = System.currentTimeMillis();
        try (Connection conn = openConnection(host, port, database, username, password);
             PreparedStatement statement = conn.prepareStatement(
                     "SELECT COUNT(*) FROM " + sanitizeIdentifier(datasetTable)
                             + " WHERE zuofeibz=0 AND LEFT(UPPER(shujujdm), LENGTH(?))=?")) {

            String prefix = normalizePrefix(datasetPrefix);
            statement.setString(1, prefix);
            statement.setString(2, prefix);
            int count = 0;
            try (ResultSet rs = statement.executeQuery()) {
                if (rs.next()) count = rs.getInt(1);
            }

            long elapsed = System.currentTimeMillis() - start;
            return Map.of(
                    "success", true,
                    "message", "连接成功，耗时 " + elapsed + "ms",
                    "datasetCount", count
            );
        } catch (SQLException e) {
            long elapsed = System.currentTimeMillis() - start;
            log.warn("[MedicalRegistryConfig] 测试连接失败: {}", e.getMessage());
            return Map.of(
                    "success", false,
                    "message", "连接失败: " + e.getMessage(),
                    "datasetCount", 0,
                    "elapsed", elapsed
            );
        }
    }

    // ── 内部方法 ──

    private Optional<String> getValue(String key) {
        return settingRepository.findById(key)
                .map(SystemSetting::getSettingValue)
                .filter(v -> v != null && !v.isBlank());
    }

    private static String buildJdbcUrl(String host, int port, String database) {
        return "jdbc:mysql://" + host + ":" + port + "/" + database
                + "?useSSL=false&characterEncoding=UTF-8&connectTimeout=5000&socketTimeout=30000";
    }

    /**
     * 简单标识符清洗，防止 SQL 注入。
     */
    private static String sanitizeIdentifier(String identifier) {
        if (identifier == null || identifier.isBlank()) return "dm_shujuji";
        return identifier.replaceAll("[^a-zA-Z0-9_]", "");
    }

    private static String normalizePrefix(String prefix) {
        if (prefix == null || prefix.isBlank()) {
            return STANDARD_DATASET_PREFIX;
        }
        return prefix.trim().toUpperCase(Locale.ROOT);
    }
}
