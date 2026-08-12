package com.dfygt.dfetl.server.service;

import com.dfygt.dfetl.server.common.AesUtil;
import com.dfygt.dfetl.server.dto.ConnectionTestResult;
import com.dfygt.dfetl.server.dto.SourceDataSourceDto;
import com.dfygt.dfetl.server.entity.SourceDataSource;
import com.dfygt.dfetl.server.medical.DatasetDefinition;
import com.dfygt.dfetl.server.medical.DatasetMatcher;
import com.dfygt.dfetl.server.medical.FieldDefinition;
import com.dfygt.dfetl.server.medical.MatchResult;
import com.dfygt.dfetl.server.medical.MedicalRegistryConfig;
import com.dfygt.dfetl.server.medical.MedicalRegistryReader;
import com.dfygt.dfetl.server.repository.SourceDataSourceRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;

@Service
@Slf4j
public class SourceDataSourceService {

    static final int DEFAULT_QUERY_TIMEOUT_SECONDS = 60;
    private static final int CONNECTION_ATTEMPTS = 3;
    private static final long CONNECTION_RETRY_BACKOFF_MS = 500L;

    private final SourceDataSourceRepository repository;
    private final AesUtil aesUtil;
    private final MedicalRegistryConfig medicalRegistryConfig;
    private final MedicalRegistryReader medicalRegistryReader;
    private final DatasetMatcher datasetMatcher;
    private final com.dfygt.dfetl.server.common.JdbcConnectionPoolManager connectionPoolManager;
    private final SourceCodeGenerator sourceCodeGenerator;
    private final SourceDataSourceCreateAttemptService createAttemptService;
    private final SourceDataSourceOwnershipValidator ownershipValidator;

    public SourceDataSourceService(
            SourceDataSourceRepository repository,
            AesUtil aesUtil,
            @Lazy MedicalRegistryConfig medicalRegistryConfig,
            @Lazy MedicalRegistryReader medicalRegistryReader,
            @Lazy DatasetMatcher datasetMatcher,
            com.dfygt.dfetl.server.common.JdbcConnectionPoolManager connectionPoolManager,
            SourceCodeGenerator sourceCodeGenerator,
            SourceDataSourceCreateAttemptService createAttemptService,
            SourceDataSourceOwnershipValidator ownershipValidator) {
        this.repository = repository;
        this.aesUtil = aesUtil;
        this.medicalRegistryConfig = medicalRegistryConfig;
        this.medicalRegistryReader = medicalRegistryReader;
        this.datasetMatcher = datasetMatcher;
        this.connectionPoolManager = connectionPoolManager;
        this.sourceCodeGenerator = sourceCodeGenerator;
        this.createAttemptService = createAttemptService;
        this.ownershipValidator = ownershipValidator;
    }

    public List<SourceDataSourceDto> findAll() {
        return repository.findAll().stream().map(this::toDto).toList();
    }

    /**
     * 按机构 ID 过滤数据源；用于支撑 {@code GET /api/datasource/source?institutionId=...}
     * 与机构维度的资产盘点（spec 064 Property 5）。
     */
    public List<SourceDataSourceDto> findByInstitutionId(Long institutionId) {
        return repository.findByInstitutionId(institutionId).stream().map(this::toDto).toList();
    }

    public SourceDataSourceDto findById(Long id) {
        return toDto(getOrThrow(id));
    }

    public SourceDataSourceDto create(SourceDataSourceDto dto) {
        // Controller 的 Bean Validation 不能覆盖 service 直调场景，create/update 统一校验归属。
        ownershipValidator.validate(dto.getInstitutionId());
        if (dto.getType() == null || dto.getType().isBlank()) {
            throw new IllegalArgumentException("库类型为必填");
        }

        // spec 070 §5.3 / §8.2:create 时由 SourceCodeGenerator 生成 source_code;
        // 每次唯一冲突必须在独立事务中回滚，然后使用全新 entity 重取下一序号，最多 3 次。
        final int maxRetry = 3;
        for (int attempt = 0; attempt < maxRetry; attempt++) {
            try {
                SourceDataSource entity = new SourceDataSource();
                copyToEntity(dto, entity);
                String code = sourceCodeGenerator.generate(dto.getInstitutionId(), dto.getType());
                entity.setSourceCode(code);
                return toDto(createAttemptService.saveInNewTransaction(entity));
            } catch (DataIntegrityViolationException e) {
                log.warn("SourceDataSourceService.create: source_code 撞唯一约束(重试 {}/{}), institutionId={} type={}",
                        attempt + 1, maxRetry, dto.getInstitutionId(), dto.getType());
                if (attempt == maxRetry - 1) {
                    throw new RuntimeException(
                            "source_code 生成失败:并发冲突连续重试 " + maxRetry + " 次仍未成功", e);
                }
            }
        }
        // 不可达:循环内要么 return 要么抛异常,这里只为绕过 javac 终止性检查。
        throw new IllegalStateException("unreachable");
    }

    @Transactional
    public SourceDataSourceDto update(Long id, SourceDataSourceDto dto) {
        SourceDataSource entity = getOrThrow(id);
        ownershipValidator.validate(dto.getInstitutionId());
        // spec 070 D5:source_code 生成后不可改;copyToEntity 不显式 set sourceCode,
        // entity 是 getOrThrow 取来的已有记录,sourceCode 自动保持原值。
        copyToEntity(dto, entity);
        return toDto(repository.save(entity));
    }

    @Transactional
    public void delete(Long id) {
        repository.deleteById(id);
    }

    public ConnectionTestResult testConnection(Long id) {
        SourceDataSource ds = getOrThrow(id);
        String password = aesUtil.decrypt(ds.getPasswordEnc());
        String jdbcUrl = buildJdbcUrl(ds);
        try (Connection conn = openJdbcConnection(jdbcUrl, ds.getUsername(), password)) {
            String version = conn.getMetaData().getDatabaseProductVersion();
            return ConnectionTestResult.ok("连接成功，数据库版本: " + version);
        } catch (Exception e) {
            log.warn("Connection test failed for datasource {}: {}", id, e.getMessage());
            return ConnectionTestResult.fail("连接失败: " + e.getMessage());
        }
    }

    /**
     * 用 DTO 临时构造连接进行测试，不落库。
     * 用于新建表单尚未保存即点击「测试连接」的场景。
     * 若 password 为空或脱敏值（编辑场景未改密码），则尝试用已存记录解密后的密码。
     */
    public ConnectionTestResult testConnectionByDto(SourceDataSourceDto dto) {
        try {
            if (dto == null || dto.getType() == null || dto.getHost() == null
                    || dto.getPort() == null || dto.getDatabase() == null
                    || dto.getUsername() == null) {
                return ConnectionTestResult.fail("缺少必要的连接参数");
            }
            String password = dto.getPassword();
            if ((password == null || password.isBlank() || "****".equals(password)) && dto.getId() != null) {
                SourceDataSource existing = repository.findById(dto.getId()).orElse(null);
                if (existing != null) {
                    password = aesUtil.decrypt(existing.getPasswordEnc());
                }
            }
            if (password == null) password = "";

            SourceDataSource probe = new SourceDataSource();
            probe.setType(dto.getType());
            probe.setHost(dto.getHost());
            probe.setPort(dto.getPort());
            probe.setDbName(dto.getDatabase());
            probe.setSsl(Boolean.TRUE.equals(dto.getSsl()));
            String jdbcUrl = buildJdbcUrl(probe);

            try (Connection conn = openJdbcConnection(jdbcUrl, dto.getUsername(), password)) {
                String version = conn.getMetaData().getDatabaseProductVersion();
                return ConnectionTestResult.ok("连接成功，数据库版本: " + version);
            }
        } catch (Exception e) {
            log.warn("Test-config connection failed for {}: {}", dto != null ? dto.getName() : "<null>", e.getMessage());
            return ConnectionTestResult.fail("连接失败: " + e.getMessage());
        }
    }

    /** 仅更新 status 字段（启用/禁用切换）。 */
    @Transactional
    public SourceDataSourceDto updateStatus(Long id, String status) {
        SourceDataSource entity = getOrThrow(id);
        entity.setStatus(status);
        return toDto(repository.save(entity));
    }

    /**
     * 打开一个源端 JDBC 连接，调用方负责 close（建议 try-with-resources）。
     * spec 020 起对外暴露，便于其他 Service 复用 JDBC URL 拼装与凭据解密逻辑。
     * <p>这是运行时热路径入口（checksum/审计/字段回读按分片反复调用），走连接池复用，
     * 避免每次新建并丢弃连接（见 ETL_RISK_REGISTER「数据源连接池统一」）。
     * 连接池获取失败时回退到带重试的直连，保留原有容错。
     */
    public Connection openConnection(Long id) throws java.sql.SQLException {
        SourceDataSource ds = getOrThrow(id);
        String password = aesUtil.decrypt(ds.getPasswordEnc());
        String jdbcUrl = buildJdbcUrl(ds);
        try {
            return connectionPoolManager.getConnection(jdbcUrl, ds.getUsername(), password);
        } catch (SQLException poolEx) {
            log.warn("openConnection via pool failed for datasource {}, fallback to direct connect: {}",
                    id, poolEx.getMessage());
            return openJdbcConnection(jdbcUrl, ds.getUsername(), password);
        }
    }

    static Connection openJdbcConnection(String jdbcUrl, String username, String password) throws SQLException {
        SQLException last = null;
        for (int attempt = 1; attempt <= CONNECTION_ATTEMPTS; attempt++) {
            try {
                return DriverManager.getConnection(jdbcUrl, username, password);
            } catch (SQLException e) {
                last = e;
                if (attempt == CONNECTION_ATTEMPTS) break;
                try {
                    Thread.sleep(CONNECTION_RETRY_BACKOFF_MS * attempt);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw e;
                }
            }
        }
        throw last;
    }

    public List<String> listSchemas(Long id) {
        SourceDataSource ds = getOrThrow(id);
        String password = aesUtil.decrypt(ds.getPasswordEnc());
        String jdbcUrl = buildJdbcUrl(ds);
        String type = ds.getType() == null ? "" : ds.getType().toUpperCase();
        List<String> schemas = new ArrayList<>();

        // Oracle 特殊处理：直接以配置用户名作为 schema（避免误把 PDB/系统 schema 当成业务 schema），
        // 同时通过 ALL_USERS 拉一份业务 schema 候选，过滤系统账号。
        if ("ORACLE".equals(type)) {
            String currentUser = ds.getUsername() == null ? "" : ds.getUsername().toUpperCase();
            if (!currentUser.isEmpty()) schemas.add(currentUser);
            try (Connection conn = openJdbcConnection(jdbcUrl, ds.getUsername(), password);
                 java.sql.Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery(
                         "SELECT USERNAME FROM ALL_USERS WHERE ORACLE_MAINTAINED='N' ORDER BY USERNAME")) {
                while (rs.next()) {
                    String u = rs.getString(1);
                    if (u != null && !u.equalsIgnoreCase(currentUser)) schemas.add(u);
                }
            } catch (Exception e) {
                log.warn("listSchemas (Oracle ALL_USERS) failed for datasource {}: {}", id, e.getMessage());
            }
            return schemas;
        }

        // PostgreSQL：直接用 has_schema_privilege 只返回当前用户能 USAGE 的 schema，
        // 避免把无权访问的 schema 暴露给用户造成误选。
        if ("POSTGRESQL".equals(type)) {
            try (Connection conn = openJdbcConnection(jdbcUrl, ds.getUsername(), password);
                 java.sql.Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery(
                         "SELECT nspname FROM pg_namespace " +
                         "WHERE has_schema_privilege(current_user, nspname, 'USAGE') " +
                         "  AND nspname NOT LIKE 'pg_%' " +
                         "  AND nspname NOT IN ('information_schema') " +
                         "ORDER BY nspname")) {
                while (rs.next()) schemas.add(rs.getString(1));
            } catch (Exception e) {
                log.warn("listSchemas (PG has_schema_privilege) failed for datasource {}: {}", id, e.getMessage());
                throw new RuntimeException("获取 Schema 列表失败: " + e.getMessage(), e);
            }
            return schemas;
        }

        try (Connection conn = openJdbcConnection(jdbcUrl, ds.getUsername(), password);
             ResultSet rs = conn.getMetaData().getSchemas()) {
            while (rs.next()) {
                String s = rs.getString("TABLE_SCHEM");
                if (isSystemSchema(type, s)) continue;
                schemas.add(s);
            }
        } catch (Exception e) {
            log.warn("listSchemas failed for datasource {}: {}", id, e.getMessage());
            throw new RuntimeException("获取 Schema 列表失败: " + e.getMessage(), e);
        }
        // MySQL 无标准 schema，回退使用 catalog
        if (schemas.isEmpty()) {
            try (Connection conn = openJdbcConnection(jdbcUrl, ds.getUsername(), password);
                 ResultSet rs = conn.getMetaData().getCatalogs()) {
                while (rs.next()) {
                    String s = rs.getString("TABLE_CAT");
                    if (isSystemSchema(type, s)) continue;
                    schemas.add(s);
                }
            } catch (Exception e) {
                log.warn("listCatalogs fallback failed for datasource {}: {}", id, e.getMessage());
            }
        }
        return schemas;
    }

    /** 过滤各类数据库的系统 schema/catalog。*/
    private static boolean isSystemSchema(String type, String name) {
        if (name == null) return true;
        String n = name.toUpperCase();
        return switch (type) {
            case "MYSQL" -> n.equals("INFORMATION_SCHEMA") || n.equals("MYSQL")
                    || n.equals("PERFORMANCE_SCHEMA") || n.equals("SYS");
            case "POSTGRESQL" -> n.equals("PG_CATALOG") || n.equals("INFORMATION_SCHEMA")
                    || n.startsWith("PG_TOAST") || n.startsWith("PG_TEMP");
            case "SQLSERVER" -> n.equals("SYS") || n.equals("INFORMATION_SCHEMA")
                    || n.equals("DB_OWNER") || n.equals("DB_ACCESSADMIN") || n.equals("DB_SECURITYADMIN")
                    || n.equals("DB_DDLADMIN") || n.equals("DB_BACKUPOPERATOR") || n.equals("DB_DATAREADER")
                    || n.equals("DB_DATAWRITER") || n.equals("DB_DENYDATAREADER") || n.equals("DB_DENYDATAWRITER")
                    || n.equals("GUEST");
            // Doris 通过 MySQL 协议暴露元数据，过滤 Doris 自身系统库
            case "DORIS" -> n.equals("INFORMATION_SCHEMA") || n.equals("MYSQL")
                    || n.equals("__INTERNAL_SCHEMA");
            default -> false;
        };
    }

    public record TableInfo(String tableName, String tableType) {}

    public List<TableInfo> listTables(Long id, String schema) {
        SourceDataSource ds = getOrThrow(id);
        String password = aesUtil.decrypt(ds.getPasswordEnc());
        String jdbcUrl = buildJdbcUrl(ds);
        List<TableInfo> tables = new ArrayList<>();
        String type = ds.getType().toUpperCase();
        // MySQL/Doris: catalog=schema, schema=null; others: catalog=null, schema=schema
        String catalog = ("MYSQL".equals(type) || "DORIS".equals(type)) ? schema : null;
        String schemaPattern = ("MYSQL".equals(type) || "DORIS".equals(type)) ? null : schema;
        try (Connection conn = openJdbcConnection(jdbcUrl, ds.getUsername(), password);
             ResultSet rs = conn.getMetaData().getTables(catalog, schemaPattern, "%",
                     new String[]{"TABLE", "VIEW"})) {
            while (rs.next()) {
                tables.add(new TableInfo(rs.getString("TABLE_NAME"), rs.getString("TABLE_TYPE")));
            }
        } catch (Exception e) {
            log.warn("listTables failed for datasource {}/{}: {}", id, schema, e.getMessage());
            throw new RuntimeException("获取表列表失败: " + e.getMessage(), e);
        }
        return tables;
    }

    public record ColumnInfo(String columnName, String dataType, boolean nullable,
                             Integer columnSize, Integer decimalDigits, boolean primaryKey,
                             Integer jdbcType) {
        // 兼容旧构造器（其他代码可能使用）
        public ColumnInfo(String columnName, String dataType, boolean nullable) {
            this(columnName, dataType, nullable, null, null, false, null);
        }

        public ColumnInfo(String columnName, String dataType, boolean nullable,
                          Integer columnSize, Integer decimalDigits, boolean primaryKey) {
            this(columnName, dataType, nullable, columnSize, decimalDigits, primaryKey, null);
        }
    }

    public List<ColumnInfo> listColumns(Long id, String schema, String table) {
        SourceDataSource ds = getOrThrow(id);
        String password = aesUtil.decrypt(ds.getPasswordEnc());
        String jdbcUrl = buildJdbcUrl(ds);
        List<ColumnInfo> columns = new ArrayList<>();
        String type = ds.getType().toUpperCase();
        String catalog = "MYSQL".equals(type) ? schema : null;
        String schemaPattern = "MYSQL".equals(type) ? null : schema;
        try (Connection conn = openJdbcConnection(jdbcUrl, ds.getUsername(), password)) {
            // 先拿主键集合
            java.util.Set<String> pkSet = new java.util.HashSet<>();
            try (ResultSet pkRs = conn.getMetaData().getPrimaryKeys(catalog, schemaPattern, table)) {
                while (pkRs.next()) {
                    pkSet.add(pkRs.getString("COLUMN_NAME"));
                }
            } catch (Exception ignored) {}
            try (ResultSet rs = conn.getMetaData().getColumns(catalog, schemaPattern, table, "%")) {
                while (rs.next()) {
                    String name = rs.getString("COLUMN_NAME");
                    int size = rs.getInt("COLUMN_SIZE");
                    int digits = rs.getInt("DECIMAL_DIGITS");
                    boolean digitsWasNull = rs.wasNull();
                    columns.add(new ColumnInfo(
                            name,
                            rs.getString("TYPE_NAME"),
                            rs.getInt("NULLABLE") == 1,
                            size > 0 ? size : null,
                            digitsWasNull ? null : digits,
                            pkSet.contains(name),
                            rs.getInt("DATA_TYPE")));
                }
            }
        } catch (Exception e) {
            log.warn("listColumns failed for datasource {}/{}.{}: {}", id, schema, table, e.getMessage());
            throw new RuntimeException("获取字段列表失败: " + e.getMessage(), e);
        }
        return columns;
    }

    public String validateCustomSql(Long id, String sql) {
        String normalized = CustomSqlValidator.requireReadOnlySelect(sql);
        SourceDataSource ds = getOrThrow(id);
        String password = aesUtil.decrypt(ds.getPasswordEnc());
        String jdbcUrl = buildJdbcUrl(ds);
        String probeSql = buildCustomSqlMetadataSql(normalized);
        try (Connection conn = openJdbcConnection(jdbcUrl, ds.getUsername(), password)) {
            prepareCustomSqlConnection(conn);
            try (java.sql.PreparedStatement ps = conn.prepareStatement(probeSql)) {
                applyQueryTimeout(ps, ds);
                ps.setMaxRows(1);
                try (ResultSet ignored = ps.executeQuery()) {
                    return "SQL 校验通过";
                }
            }
        } catch (Exception e) {
            log.warn("validateCustomSql failed for datasource {}: {}", id, e.getMessage());
            throw new RuntimeException("自定义 SQL 校验失败: " + e.getMessage(), e);
        }
    }

    public List<ColumnInfo> listCustomSqlColumns(Long id, String sql) {
        String normalized = CustomSqlValidator.requireReadOnlySelect(sql);
        SourceDataSource ds = getOrThrow(id);
        String password = aesUtil.decrypt(ds.getPasswordEnc());
        String jdbcUrl = buildJdbcUrl(ds);
        List<ColumnInfo> columns = new ArrayList<>();
        try (Connection conn = openJdbcConnection(jdbcUrl, ds.getUsername(), password)) {
            prepareCustomSqlConnection(conn);
            try (java.sql.PreparedStatement ps = conn.prepareStatement(buildCustomSqlMetadataSql(normalized))) {
                applyQueryTimeout(ps, ds);
                ps.setMaxRows(1);
                try (ResultSet rs = ps.executeQuery()) {
                    ResultSetMetaData meta = rs.getMetaData();
                    int colCount = meta.getColumnCount();
                    for (int i = 1; i <= colCount; i++) {
                        String label = meta.getColumnLabel(i);
                        if (label == null || label.isBlank()) label = meta.getColumnName(i);
                        int size = meta.getPrecision(i);
                        int digits = meta.getScale(i);
                        columns.add(new ColumnInfo(
                                label,
                                meta.getColumnTypeName(i),
                                meta.isNullable(i) != ResultSetMetaData.columnNoNulls,
                                size > 0 ? size : null,
                                digits >= 0 ? digits : null,
                                false,
                                meta.getColumnType(i)));
                    }
                }
            }
        } catch (Exception e) {
            log.warn("listCustomSqlColumns failed for datasource {}: {}", id, e.getMessage());
            throw new RuntimeException("获取自定义 SQL 字段失败: " + e.getMessage(), e);
        }
        return columns;
    }

    public List<java.util.LinkedHashMap<String, String>> previewCustomSql(Long id, String sql, int limit) {
        if (limit <= 0 || limit > 20) limit = 10;
        String normalized = CustomSqlValidator.requireReadOnlySelect(sql);
        SourceDataSource ds = getOrThrow(id);
        String type = ds.getType() == null ? "" : ds.getType().toUpperCase();
        String password = aesUtil.decrypt(ds.getPasswordEnc());
        String jdbcUrl = buildJdbcUrl(ds);
        String previewSql = buildCustomSqlPreviewSql(type, normalized, limit);

        List<java.util.LinkedHashMap<String, String>> rows = new ArrayList<>();
        try (Connection conn = openJdbcConnection(jdbcUrl, ds.getUsername(), password)) {
            prepareCustomSqlConnection(conn);
            try (java.sql.Statement stmt = conn.createStatement()) {
                applyQueryTimeout(stmt, ds);
                try (ResultSet rs = stmt.executeQuery(previewSql)) {
                    ResultSetMetaData meta = rs.getMetaData();
                    int colCount = meta.getColumnCount();
                    while (rs.next()) {
                        java.util.LinkedHashMap<String, String> row = new java.util.LinkedHashMap<>();
                        for (int i = 1; i <= colCount; i++) {
                            Object val = rs.getObject(i);
                            row.put(meta.getColumnLabel(i), val == null ? null : val.toString());
                        }
                        rows.add(row);
                    }
                }
            }
        } catch (Exception e) {
            log.warn("previewCustomSql failed ds={}: {}", id, e.getMessage());
            throw new RuntimeException("获取自定义 SQL 样本数据失败: " + e.getMessage(), e);
        }
        return rows;
    }

    public long getCustomSqlRowCount(Long id, String sql) {
        String normalized = CustomSqlValidator.requireReadOnlySelect(sql);
        SourceDataSource ds = getOrThrow(id);
        String password = aesUtil.decrypt(ds.getPasswordEnc());
        String jdbcUrl = buildJdbcUrl(ds);
        String countSql = "SELECT COUNT(*) FROM (\n" + normalized + "\n) dfetl_sql_count";
        try (Connection conn = openJdbcConnection(jdbcUrl, ds.getUsername(), password)) {
            prepareCustomSqlConnection(conn);
            try (java.sql.Statement stmt = conn.createStatement()) {
                applyQueryTimeout(stmt, ds);
                try (ResultSet rs = stmt.executeQuery(countSql)) {
                    return rs.next() ? rs.getLong(1) : 0L;
                }
            }
        } catch (Exception e) {
            log.warn("getCustomSqlRowCount failed ds={}: {}", id, e.getMessage());
            throw new RuntimeException("查询自定义 SQL 行数失败: " + e.getMessage(), e);
        }
    }

    private static String buildCustomSqlMetadataSql(String sql) {
        return "SELECT * FROM (\n" + sql + "\n) dfetl_sql_probe WHERE 1 = 0";
    }

    private static String buildCustomSqlPreviewSql(String type, String sql, int limit) {
        return switch (type) {
            case "ORACLE" -> "SELECT * FROM (\n" + sql + "\n) dfetl_sql_preview WHERE ROWNUM <= " + limit;
            case "SQLSERVER" -> "SELECT TOP " + limit + " * FROM (\n" + sql + "\n) dfetl_sql_preview";
            default -> "SELECT * FROM (\n" + sql + "\n) dfetl_sql_preview LIMIT " + limit;
        };
    }

    public static void applyQueryTimeout(Statement statement, SourceDataSource ds) throws SQLException {
        int timeout = ds != null && ds.getQueryTimeout() != null && ds.getQueryTimeout() > 0
                ? ds.getQueryTimeout()
                : DEFAULT_QUERY_TIMEOUT_SECONDS;
        statement.setQueryTimeout(timeout);
    }

    public static void prepareCustomSqlConnection(Connection connection) throws SQLException {
        connection.setReadOnly(true);
    }

    /**
     * 查询源端表/视图的 COUNT(*) 行数。
     * 用于任务创建向导等全量对象探测场景；任务监控页使用 execution count 语义。
     */
    public long getTableRowCount(Long id, String schema, String table) {
        SourceDataSource ds = getOrThrow(id);
        String password = aesUtil.decrypt(ds.getPasswordEnc());
        String jdbcUrl = buildJdbcUrl(ds);
        String type = ds.getType().toUpperCase();
        // 构造安全的 SQL（schema/table 已在调用端按白名单校验，但此处做基本防护）
        if (!isIdentifierSafe(schema) || !isIdentifierSafe(table)) {
            throw new IllegalArgumentException("非法标识符: schema=" + schema + ", table=" + table);
        }
        String sql = buildCountSql(type, schema, table);
        try (Connection conn = openJdbcConnection(jdbcUrl, ds.getUsername(), password);
             java.sql.Statement stmt = conn.createStatement()) {
            applyQueryTimeout(stmt, ds);
            try (ResultSet rs = stmt.executeQuery(sql)) {
                return rs.next() ? rs.getLong(1) : 0L;
            }
        } catch (Exception e) {
            log.warn("getTableRowCount failed for datasource {}/{}.{}: {}", id, schema, table, e.getMessage());
            throw new RuntimeException("查询行数失败: " + e.getMessage(), e);
        }
    }

    private static String buildCountSql(String type, String schema, String table) {
        return switch (type) {
            case "MYSQL"      -> String.format("SELECT COUNT(*) FROM `%s`.`%s`", schema, table);
            case "POSTGRESQL" -> String.format("SELECT COUNT(*) FROM \"%s\".\"%s\"", schema, table);
            case "ORACLE"     -> schema != null && !schema.isBlank()
                    ? String.format("SELECT COUNT(*) FROM \"%s\".\"%s\"", schema, table)
                    : String.format("SELECT COUNT(*) FROM \"%s\"", table);
            case "SQLSERVER"  -> schema != null && !schema.isBlank()
                    ? String.format("SELECT COUNT(*) FROM [%s].[%s]", schema, table)
                    : String.format("SELECT COUNT(*) FROM [%s]", table);
            // Doris 用反引号转义；schema 即为 database
            case "DORIS"      -> schema != null && !schema.isBlank()
                    ? String.format("SELECT COUNT(*) FROM `%s`.`%s`", schema, table)
                    : String.format("SELECT COUNT(*) FROM `%s`", table);
            default           -> String.format("SELECT COUNT(*) FROM %s.%s", schema, table);
        };
    }

    /** 只允许字母、数字、下划线、横线、点，防止 SQL 注入 */
    private static boolean isIdentifierSafe(String s) {
        if (s == null || s.isBlank()) return true;   // 允许空（Oracle 无 schema 场景）
        return s.matches("[\\w\\-\\.]+");
    }

    // ── spec 040：视图档位评估 ────────────────────────────────────────

    /**
     * 视图档位评估结果。
     * tier: A=物理表/物化视图，B=简单视图，C=复杂视图（仅 FULL），D=不支持
     * reasons: 推断依据列表，便于前端展示
     */
    public record ViewAcceptance(
            String tier,
            java.util.List<String> reasons,
            MedicalRecommend medicalRecommend
    ) {
        /** 向后兼容：无规范推荐时使用 */
        public ViewAcceptance(String tier, java.util.List<String> reasons) {
            this(tier, reasons, null);
        }
    }

    /**
     * 医共体规范推荐配置（从规范定义自动提取）。
     * 前端可用此信息自动预填同步策略表单。
     */
    public record MedicalRecommend(
            java.util.List<String> upsertKeys,
            String incrementalField,
            String softDeleteField,
            String syncMode,
            String dorisTableModel,
            boolean enableDorisMerge
    ) {}

    /**
     * 评估指定 schema/object 的视图准入档位。
     * 启发式：
     *   0. 如果医共体规范库已启用，优先用规范定义判定档位
     *   1. JDBC TABLE_TYPE 元数据 → 物理表 / MATERIALIZED VIEW → A
     *   2. 取 view_definition DDL → 含 JOIN/UNION/GROUP BY/DISTINCT → C
     *   3. listColumns 探测 → 全 nullable + 无 PK → 倾向 C；有 PK → B
     *   4. DDL 获取失败且无法确认业务键 → D（fail-closed，需人工核对后修复元数据能力）
     *   5. 完全无法识别 → D
     */
    public ViewAcceptance evaluateViewAcceptance(Long id, String schema, String table) {
        if (!isIdentifierSafe(schema) || !isIdentifierSafe(table)) {
            throw new IllegalArgumentException("非法标识符: schema=" + schema + ", table=" + table);
        }
        SourceDataSource ds = getOrThrow(id);
        String type = ds.getType().toUpperCase();
        java.util.List<String> reasons = new java.util.ArrayList<>();

        // step 1：判定对象类型
        String objectType = probeObjectType(ds, schema, table);
        if (objectType == null) {
            reasons.add("元数据未找到对象 " + schema + "." + table);
            return new ViewAcceptance("D", reasons);
        }
        if ("TABLE".equalsIgnoreCase(objectType) || objectType.toUpperCase().contains("MATERIALIZED")) {
            reasons.add("对象类型: " + objectType);
            return new ViewAcceptance("A", reasons);
        }

        // step 1.5：医共体规范定义匹配（优先于 JDBC 启发式）
        ViewAcceptance registryResult = evaluateByMedicalRegistry(table, reasons);
        if (registryResult != null) {
            return registryResult;
        }

        // step 2：取视图 DDL（回退到 JDBC 启发式逻辑）
        String ddl = null;
        try {
            ddl = fetchViewDefinition(ds, schema, table);
        } catch (Exception e) {
            log.warn("fetchViewDefinition failed for {}.{}: {}", schema, table, e.getMessage());
            reasons.add("视图定义获取失败，按列元数据降级判断");
        }
        boolean ddlComplex = false;
        if (ddl != null) {
            String lower = ddl.toLowerCase(java.util.Locale.ROOT);
            // 简单关键字启发式（误判由前端「手动放行」按钮兜底）
            if (lower.contains(" join ")     ) { reasons.add("DDL 含 JOIN");      ddlComplex = true; }
            if (lower.contains(" union ")    ) { reasons.add("DDL 含 UNION");     ddlComplex = true; }
            if (lower.contains(" group by ") ) { reasons.add("DDL 含 GROUP BY");  ddlComplex = true; }
            if (lower.contains(" distinct ") ) { reasons.add("DDL 含 DISTINCT");  ddlComplex = true; }
        }

        // step 3：列元数据辅助判断
        boolean hasPk = false;
        boolean allNullable = true;
        boolean hasIncrementalField = false;
        try {
            java.util.List<ColumnInfo> cols = listColumns(id, schema, table);
            if (!cols.isEmpty()) {
                for (ColumnInfo c : cols) {
                    if (c.primaryKey()) hasPk = true;
                    if (!c.nullable())  allNullable = false;
                    // 检测常见增量时间字段（说明视图支持增量同步）
                    String colLower = c.columnName() == null ? "" : c.columnName().toLowerCase(java.util.Locale.ROOT);
                    if (colLower.contains("xiugaisj") || colLower.contains("gengxinsj")
                            || colLower.contains("update_time") || colLower.contains("updated_at")
                            || colLower.contains("modify_time") || colLower.contains("last_modified")) {
                        hasIncrementalField = true;
                    }
                }
                if (hasPk)              reasons.add("列含主键");
                if (allNullable)        reasons.add("所有列均 nullable");
                if (hasIncrementalField) reasons.add("含增量时间字段");
            }
        } catch (Exception e) {
            log.warn("listColumns probe failed for view {}.{}: {}", schema, table, e.getMessage());
            reasons.add("列元数据探测失败");
        }

        // 综合判定
        // PostgreSQL 视图的 JDBC 元数据永远无主键且全 nullable，不能仅凭此判 C 档。
        // 如果含增量时间字段，说明视图设计上支持增量同步，判 B 档。
        if (ddlComplex) {
            if (hasPk || !allNullable || hasIncrementalField) {
                reasons.add("DDL 含复杂语法但有业务键/增量字段，判定为 B 档（可增量）");
                return new ViewAcceptance("B", reasons);
            }
            return new ViewAcceptance("C", reasons);
        }
        if (!hasPk && allNullable && !hasIncrementalField && ddl != null) return new ViewAcceptance("C", reasons);
        if (ddl == null && !hasPk) {
            // DDL 未拿到 + 列也无主键，无法证明该视图支持可靠同步，必须 fail-closed。
            reasons.add("DDL 与业务键均无法确认，按 D 档拒绝（请修复元数据权限或人工核对）");
            return new ViewAcceptance("D", reasons);
        }
        return new ViewAcceptance("B", reasons);
    }

    /**
     * 使用医共体规范定义评估视图档位。
     * <p>
     * 如果规范库已启用且匹配成功，从 FieldDefinition 中提取：
     * - 业务唯一键：primaryKey=true 的字段
     * - 时间字段：字段名含 xiugaisj 或 gengxinsj 且类型为 D/DT
     * - 作废标志：字段名为 zuofeibz
     * </p>
     *
     * @param viewName 视图名称
     * @param reasons  推断依据列表（会追加内容）
     * @return 匹配成功时返回 ViewAcceptance；未启用或匹配失败返回 null（回退到 JDBC 启发式）
     */
    private ViewAcceptance evaluateByMedicalRegistry(String viewName, java.util.List<String> reasons) {
        try {
            if (!medicalRegistryConfig.isEnabled() || !medicalRegistryConfig.isConfigured()) {
                return null;
            }

            List<DatasetDefinition> datasets = medicalRegistryReader.loadDatasets();
            if (datasets.isEmpty()) {
                return null;
            }

            Optional<MatchResult> matchOpt = datasetMatcher.match(viewName, datasets);
            if (matchOpt.isEmpty()) {
                return null;
            }

            MatchResult match = matchOpt.get();
            DatasetDefinition dataset = match.dataset();
            reasons.add("规范定义匹配: " + dataset.shujujdm() + " (" + match.matchType() + ")");

            // 提取业务唯一键
            List<String> pkFields = dataset.fields().stream()
                    .filter(FieldDefinition::primaryKey)
                    .map(f -> f.ziduandm().toLowerCase())
                    .toList();
            boolean hasPrimaryKey = !pkFields.isEmpty();
            if (hasPrimaryKey) {
                reasons.add("规范定义业务唯一键: " + String.join(", ", pkFields));
            }

            // 提取时间字段：字段名含 xiugaisj 或 gengxinsj 且类型为 D/DT
            List<String> timeFields = dataset.fields().stream()
                    .filter(f -> {
                        String code = f.ziduandm().toLowerCase();
                        String sdvType = f.sdvType() != null ? f.sdvType().toUpperCase() : "";
                        return (code.contains("xiugaisj") || code.contains("gengxinsj"))
                                && ("D".equals(sdvType) || "DT".equals(sdvType));
                    })
                    .map(f -> f.ziduandm().toLowerCase())
                    .toList();
            boolean hasTimeField = !timeFields.isEmpty();
            if (hasTimeField) {
                reasons.add("规范定义时间字段: " + String.join(", ", timeFields));
            }

            // 提取作废标志
            boolean hasZuofeibz = dataset.fields().stream()
                    .anyMatch(f -> "zuofeibz".equalsIgnoreCase(f.ziduandm()));
            if (hasZuofeibz) {
                reasons.add("规范定义含作废标志字段 (zuofeibz)");
            }

            // 构建规范推荐配置
            String incrField = hasTimeField ? timeFields.get(0) : null;
            String softDelete = hasZuofeibz ? "zuofeibz" : null;
            String recSyncMode = hasPrimaryKey ? "UPSERT" : "TRUNCATE";
            String recDorisModel = hasPrimaryKey ? "UNIQUE_KEY" : "DUPLICATE_KEY";
            boolean recMerge = hasPrimaryKey && !hasZuofeibz;
            MedicalRecommend medRec = new MedicalRecommend(
                    pkFields, incrField, softDelete, recSyncMode, recDorisModel, recMerge);

            // 档位判定
            if (hasPrimaryKey && hasTimeField) {
                reasons.add("有唯一键 + 有时间字段 → B 档（支持增量同步）");
                return new ViewAcceptance("B", reasons, medRec);
            } else if (hasPrimaryKey) {
                reasons.add("有唯一键 + 无时间字段 → B 档（推荐全量同步）");
                return new ViewAcceptance("B", reasons, medRec);
            } else {
                reasons.add("无唯一键 → C 档（仅支持全量同步）");
                return new ViewAcceptance("C", reasons, medRec);
            }
        } catch (Exception e) {
            log.warn("[MedicalRegistry] 规范定义匹配异常，回退到 JDBC 启发式: {}", e.getMessage());
            reasons.add("规范定义匹配异常，回退到 JDBC 启发式");
            return null;
        }
    }

    /** 从 JDBC 元数据探测对象类型（TABLE / VIEW / MATERIALIZED VIEW / SYNONYM 等） */
    private String probeObjectType(SourceDataSource ds, String schema, String table) {
        String password = aesUtil.decrypt(ds.getPasswordEnc());
        String jdbcUrl = buildJdbcUrl(ds);
        String type = ds.getType().toUpperCase();
        String catalog = "MYSQL".equals(type) ? schema : null;
        String schemaPattern = "MYSQL".equals(type) ? null : schema;
        try (Connection conn = openJdbcConnection(jdbcUrl, ds.getUsername(), password);
             ResultSet rs = conn.getMetaData().getTables(catalog, schemaPattern, table, null)) {
            if (rs.next()) return rs.getString("TABLE_TYPE");
        } catch (Exception e) {
            log.warn("probeObjectType failed for {}.{}: {}", schema, table, e.getMessage());
        }
        return null;
    }

    /** 各方言查 view_definition；失败抛异常由调用方降级 */
    private String fetchViewDefinition(SourceDataSource ds, String schema, String table) throws Exception {
        String password = aesUtil.decrypt(ds.getPasswordEnc());
        String jdbcUrl = buildJdbcUrl(ds);
        String type = ds.getType().toUpperCase();
        String sql;
        switch (type) {
            case "MYSQL" -> sql = "SELECT VIEW_DEFINITION FROM information_schema.VIEWS "
                    + "WHERE TABLE_SCHEMA = ? AND TABLE_NAME = ?";
            case "POSTGRESQL" -> sql = "SELECT view_definition FROM information_schema.views "
                    + "WHERE table_schema = ? AND table_name = ?";
            case "SQLSERVER" -> sql = "SELECT VIEW_DEFINITION FROM INFORMATION_SCHEMA.VIEWS "
                    + "WHERE TABLE_SCHEMA = ? AND TABLE_NAME = ?";
            case "ORACLE" -> sql = "SELECT TEXT FROM ALL_VIEWS WHERE OWNER = ? AND VIEW_NAME = ?";
            default -> { return null; }
        }
        try (Connection conn = openJdbcConnection(jdbcUrl, ds.getUsername(), password);
             java.sql.PreparedStatement ps = conn.prepareStatement(sql)) {
            // Oracle 默认大写 owner / view_name
            String s = "ORACLE".equals(type) && schema != null ? schema.toUpperCase() : schema;
            String t = "ORACLE".equals(type) ? table.toUpperCase() : table;
            ps.setString(1, s);
            ps.setString(2, t);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return rs.getString(1);
            }
        }
        return null;
    }

    // ── private helpers ────────────────────────────────────────────────

    private SourceDataSource getOrThrow(Long id) {
        return repository.findById(id)
                .orElseThrow(() -> new NoSuchElementException("SourceDataSource not found: " + id));
    }

    /**
     * 把 DTO 的字段拷贝到 entity 上,供 create/update 共用。
     *
     * <p><b>spec 070 source_code 处理约定:</b>本方法<b>不处理</b> {@code sourceCode} 字段。
     * <ul>
     *   <li>create 路径:entity 为新建实例,{@code sourceCode} 默认为 {@code null};
     *       由 {@link #create} 主流程在调用本方法后,通过 {@link SourceCodeGenerator#generate}
     *       生成并显式 {@code setSourceCode},随后 save。</li>
     *   <li>update 路径:entity 由 {@link #getOrThrow} 取自数据库,已有 {@code sourceCode} 值;
     *       本方法不显式 set 即可天然保留原值,符合 spec 070 D5「生成后不可改」要求。</li>
     *   <li>客户端 DTO 中传入的 {@code sourceCode} 一律忽略(DTO 层注释也声明了此约束)。</li>
     * </ul>
     */
    private void copyToEntity(SourceDataSourceDto dto, SourceDataSource entity) {
        entity.setName(dto.getName());
        entity.setType(dto.getType());
        entity.setHost(dto.getHost());
        entity.setPort(dto.getPort());
        entity.setDbName(dto.getDatabase());
        entity.setUsername(dto.getUsername());
        // 仅当请求中携带非空、非脱敏密码时才更新
        if (dto.getPassword() != null && !dto.getPassword().isBlank() && !dto.getPassword().equals("****")) {
            entity.setPasswordEnc(aesUtil.encrypt(dto.getPassword()));
        }
        entity.setSchemaName(dto.getSchema());
        if (dto.getReadonly() != null)        entity.setReadonly(dto.getReadonly());
        if (dto.getQueryTimeout() != null)    entity.setQueryTimeout(dto.getQueryTimeout());
        if (dto.getReadConcurrency() != null) entity.setReadConcurrency(dto.getReadConcurrency());
        if (dto.getPoolSize() != null)        entity.setPoolSize(dto.getPoolSize());
        if (dto.getSsl() != null)             entity.setSsl(dto.getSsl());
        entity.setDescription(dto.getDescription());
        if (dto.getStatus() != null)          entity.setStatus(dto.getStatus());
        entity.setInstitutionId(dto.getInstitutionId());
    }

    private SourceDataSourceDto toDto(SourceDataSource e) {
        SourceDataSourceDto dto = new SourceDataSourceDto();
        dto.setId(e.getId());
        dto.setName(e.getName());
        dto.setType(e.getType());
        dto.setHost(e.getHost());
        dto.setPort(e.getPort());
        dto.setDatabase(e.getDbName());
        dto.setUsername(e.getUsername());
        dto.setPassword(AesUtil.mask(e.getPasswordEnc()));
        dto.setSchema(e.getSchemaName());
        dto.setReadonly(e.getReadonly());
        dto.setQueryTimeout(e.getQueryTimeout());
        dto.setReadConcurrency(e.getReadConcurrency());
        dto.setPoolSize(e.getPoolSize());
        dto.setSsl(e.getSsl());
        dto.setDescription(e.getDescription());
        dto.setStatus(e.getStatus());
        dto.setInstitutionId(e.getInstitutionId());
        // spec 070：透出系统生成的数据源稳定编码（仅响应方向，不接收客户端输入）
        dto.setSourceCode(e.getSourceCode());
        dto.setCreatedAt(e.getCreatedAt());
        dto.setUpdatedAt(e.getUpdatedAt());
        return dto;
    }

    private String buildJdbcUrl(SourceDataSource ds) {
        return switch (ds.getType().toUpperCase()) {
            case "MYSQL" -> String.format(
                    "jdbc:mysql://%s:%d/%s?useSSL=%b&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true&connectTimeout=30000&socketTimeout=60000",
                    ds.getHost(), ds.getPort(), ds.getDbName(), ds.getSsl());
            case "POSTGRESQL" -> String.format(
                    "jdbc:postgresql://%s:%d/%s",
                    ds.getHost(), ds.getPort(), ds.getDbName());
            case "ORACLE" -> String.format(
                    "jdbc:oracle:thin:@//%s:%d/%s",
                    ds.getHost(), ds.getPort(), ds.getDbName());
            case "SQLSERVER" -> String.format(
                    "jdbc:sqlserver://%s:%d;databaseName=%s;encrypt=%b;trustServerCertificate=true",
                    ds.getHost(), ds.getPort(), ds.getDbName(), ds.getSsl());
            // Doris 通过 MySQL 协议（FE Query 端口，默认 9030）；不传 serverTimezone 等 MySQL 专属参数
            case "DORIS" -> String.format(
                    "jdbc:mysql://%s:%d/%s?useSSL=false&allowPublicKeyRetrieval=true&connectTimeout=30000&socketTimeout=60000",
                    ds.getHost(), ds.getPort(), ds.getDbName());
            default -> throw new IllegalArgumentException("Unsupported datasource type: " + ds.getType());
        };
    }

    /**
     * Spec 052：查询源表前 N 行样本数据，用于 WizardModal 预览。
     * schema/table 仅允许 [\\w$#]+ 字符，防止 SQL 注入。
     */
    public List<java.util.LinkedHashMap<String, String>> previewData(
            Long id, String schema, String table, int limit) {
        if (limit <= 0 || limit > 20) limit = 10;
        if (!schema.matches("[\\w$#]+") || !table.matches("[\\w$#]+")) {
            throw new IllegalArgumentException("schema/table 包含非法字符");
        }
        SourceDataSource ds = getOrThrow(id);
        String type     = ds.getType() == null ? "" : ds.getType().toUpperCase();
        String password = aesUtil.decrypt(ds.getPasswordEnc());
        String jdbcUrl  = buildJdbcUrl(ds);

        String sql = switch (type) {
            case "ORACLE"    -> String.format(
                    "SELECT * FROM \"%s\".\"%s\" WHERE ROWNUM <= %d",
                    schema.toUpperCase(), table.toUpperCase(), limit);
            case "SQLSERVER" -> String.format(
                    "SELECT TOP %d * FROM [%s].[%s]", limit, schema, table);
            case "POSTGRESQL" -> String.format(
                    "SELECT * FROM \"%s\".\"%s\" LIMIT %d", schema, table, limit);
            default          -> String.format(
                    "SELECT * FROM `%s`.`%s` LIMIT %d", schema, table, limit);
        };

        List<java.util.LinkedHashMap<String, String>> rows = new ArrayList<>();
        try (Connection conn = openJdbcConnection(jdbcUrl, ds.getUsername(), password);
             java.sql.Statement stmt = conn.createStatement()) {
            applyQueryTimeout(stmt, ds);
            try (ResultSet rs = stmt.executeQuery(sql)) {
                ResultSetMetaData meta = rs.getMetaData();
                int colCount = meta.getColumnCount();
                while (rs.next()) {
                    java.util.LinkedHashMap<String, String> row = new java.util.LinkedHashMap<>();
                    for (int i = 1; i <= colCount; i++) {
                        Object val = rs.getObject(i);
                        row.put(meta.getColumnLabel(i), val == null ? null : val.toString());
                    }
                    rows.add(row);
                }
            }
        } catch (Exception e) {
            log.warn("previewData failed ds={} {}.{}: {}", id, schema, table, e.getMessage());
            throw new RuntimeException("获取样本数据失败: " + e.getMessage(), e);
        }
        return rows;
    }

    /**
     * 将任务配置中的列名映射回 JDBC 元数据中的原始大小写。
     * 用于构建源端 SQL 时确保列名精确匹配。
     *
     * @param dsId 源数据源 ID
     * @param schema schema 名
     * @param table 表/视图名
     * @param fieldName 任务配置中的列名（可能是小写）
     * @return JDBC 原始大小写的列名；找不到时返回原值
     */
    public String resolveOriginalColumnName(Long dsId, String schema, String table, String fieldName) {
        if (fieldName == null || fieldName.isBlank()) return fieldName;
        try {
            List<ColumnInfo> cols = listColumns(dsId, schema, table);
            for (ColumnInfo col : cols) {
                if (col.columnName() != null && col.columnName().equalsIgnoreCase(fieldName)) {
                    return col.columnName();
                }
            }
        } catch (Exception e) {
            log.warn("resolveOriginalColumnName failed for ds={} {}.{} field={}: {}",
                    dsId, schema, table, fieldName, e.getMessage());
        }
        return fieldName;
    }
}
