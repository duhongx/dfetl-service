package com.dfygt.dfetl.server.engine.doris;

import com.dfygt.dfetl.server.common.AesUtil;
import com.dfygt.dfetl.server.common.IdentifierSanitizer;
import com.dfygt.dfetl.server.common.TargetTableMapParser;
import com.dfygt.dfetl.server.entity.SyncTask;
import com.dfygt.dfetl.server.entity.TargetDataSource;
import com.dfygt.dfetl.server.medical.MedicalDatasetContract;
import com.dfygt.dfetl.server.medical.MedicalContractSnapshotCodec;
import com.dfygt.dfetl.server.medical.MedicalDatasetContractService;
import com.dfygt.dfetl.server.medical.MedicalFieldContract;
import com.dfygt.dfetl.server.repository.TargetDataSourceRepository;
import com.dfygt.dfetl.server.service.EtlSystemFieldsService;
import com.dfygt.dfetl.server.service.SourceDataSourceService;
import com.dfygt.dfetl.server.service.SourceDataSourceService.ColumnInfo;
import com.dfygt.dfetl.server.service.SourceSchemaResolver;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 任务执行前确保 Doris 目标表存在；不存在时按 {@link DorisDdlBuilder} 自动建表。
 *
 * <p>使用 Doris MySQL 协议端口（fePort，默认 9030）执行 DDL。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DorisTableEnsurer {

    private final TargetDataSourceRepository targetRepo;
    private final SourceDataSourceService sourceDataSourceService;
    private final DorisDdlBuilder ddlBuilder;
    private final EtlSystemFieldsService etlSystemFieldsService;
    private final AesUtil aesUtil;
    private final ObjectMapper objectMapper;
    private final com.dfygt.dfetl.server.common.JdbcConnectionPoolManager connectionPoolManager;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private DorisTypeMappingPolicy typeMappingPolicy = new DorisTypeMappingPolicy();

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private DorisTypeMappingRuleService typeMappingRuleService;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.dfygt.dfetl.server.repository.SystemSettingRepository systemSettingRepository;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private MedicalDatasetContractService medicalContractService;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private MedicalDorisDdlBuilder medicalDdlBuilder;

    private final DorisDdlBuilder keyInspector = new DorisDdlBuilder();

    /**
     * 确保任务的所有目标表存在；不存在则自动建表。
     * 对于多表任务（理论上拆分后每个 task 只有 1 个 viewName），逐表处理。
     *
     * @param task 同步任务
     */
    public void ensureTargetTables(SyncTask task) {
        if (task.getViewNames() == null || task.getViewNames().isEmpty()) return;
        TargetDataSource tgt = targetRepo.findById(task.getTargetDataSourceId()).orElse(null);
        if (tgt == null) {
            log.warn("DorisTableEnsurer: target datasource {} not found, skip", task.getTargetDataSourceId());
            return;
        }
        for (String srcTable : task.getViewNames()) {
            String tgtTable = resolveTargetTableName(task, srcTable);
            try {
                ensureOne(task, tgt, srcTable, tgtTable);
            } catch (Exception e) {
                log.error("DorisTableEnsurer: ensure target table failed task={} srcTable={} tgtTable={} err={}",
                        task.getId(), srcTable, tgtTable, e.getMessage(), e);
                throw new RuntimeException("自动建表失败 [" + tgtTable + "]: " + e.getMessage(), e);
            }
        }
    }

    private void ensureOne(SyncTask task, TargetDataSource tgt, String srcTable, String tgtTable) throws Exception {
        String medicalDatasetCode = medicalDatasetCode(task);
        if (medicalDatasetCode != null) {
            MedicalDatasetContract contract = loadMedicalContract(task, medicalDatasetCode);
            if (tableExists(tgt, tgtTable)) {
                validateExistingMedicalContractTable(tgt, tgtTable, contract);
                log.debug("DorisTableEnsurer: medical contract table already exists and contract validated. db={} table={} dataset={}",
                        tgt.getDbName(), tgtTable, medicalDatasetCode);
                return;
            }
            Map<String, String> etlFields = enabledEtlFields();
            String ddl = requireMedicalDdlBuilder().buildCreateTable(tgt.getDbName(), contract, etlFields);
            log.info("DorisTableEnsurer: creating medical contract target table dataset={}\n{}",
                    medicalDatasetCode, ddl);
            executeDdl(tgt, ddl);
            return;
        }

        if (tableExists(tgt, tgtTable)) {
            // 规范驱动创建的任务（spec 069）：表已由 provision 按规范建好，
            // 源端视图与规范的类型/长度/可空性天然不一致，跳过严格校验。
            // 只做缺失字段自动补齐。
            boolean isMedical = isMedicalRegistryTask(task);
            log.info("DorisTableEnsurer: ensureOne table={} isMedicalRegistry={} dataCharacteristics={}",
                    tgtTable, isMedical, task.getDataCharacteristics());
            if (isMedical) {
                List<ColumnInfo> columns = loadSourceColumns(task, srcTable);
                if (!columns.isEmpty()) {
                    Set<String> expected = expectedDorisColumns(columns);
                    Map<String, TargetColumnMeta> actual = loadTargetColumns(tgt, tgtTable);
                    List<String> missing = new ArrayList<>();
                    for (String column : expected) {
                        if (!actual.containsKey(column)) {
                            missing.add(column);
                        }
                    }
                    if (!missing.isEmpty()) {
                        log.warn("DorisTableEnsurer: medical-registry task, table={}.{} 缺失字段 {}，自动补齐",
                                tgt.getDbName(), tgtTable, missing);
                        try (Connection alterConn = openConnection(tgt);
                             Statement alterStmt = alterConn.createStatement()) {
                            // 注入防御：dbName / tgtTable 拼到反引号 DDL 前必须经字符集白名单
                            String dbName = IdentifierSanitizer.requireValid(tgt.getDbName(), "tgt.dbName");
                            String safeTgtTable = IdentifierSanitizer.requireValid(tgtTable, "tgtTable");
                            List<String> failedCols = new ArrayList<>();
                            for (String col : missing) {
                                String colType = resolveColumnTypeForAdd(col, columns);
                                String safeCol = IdentifierSanitizer.requireValid(col, "missingColumn");
                                // 使用 IF NOT EXISTS 让 ALTER 幂等（Doris 2.0+ 支持），
                                // 配合外层 partial-applied 治理（详见下文 P2 修复说明）
                                String alterSql = "ALTER TABLE `" + dbName + "`.`" + safeTgtTable
                                        + "` ADD COLUMN IF NOT EXISTS `" + safeCol + "` " + colType + " NULL";
                                try {
                                    alterStmt.execute(alterSql);
                                    log.info("DorisTableEnsurer: ADD COLUMN `{}` {} 成功", safeCol, colType);
                                } catch (Exception e) {
                                    log.warn("DorisTableEnsurer: ADD COLUMN `{}` 失败: {}", safeCol, e.getMessage());
                                    failedCols.add(safeCol + "(" + e.getMessage() + ")");
                                }
                            }
                            if (!failedCols.isEmpty()) {
                                throw new IllegalStateException("Doris 目标表 " + safeTgtTable + " 自动补列失败: " + failedCols
                                        + "，请手动执行 ALTER TABLE 或检查 Doris 状态");
                            }
                        }
                    }
                }
                log.debug("DorisTableEnsurer: medical-registry task, skip type/contract validation. table={}", tgtTable);
                return;
            }
            List<ColumnInfo> columns = loadSourceColumns(task, srcTable);
            if (columns.isEmpty()) {
                throw new IllegalStateException("源表字段为空，无法校验 Doris 目标表字段: " + srcTable);
            }
            validateExistingTableColumns(tgt, tgtTable, columns, sourceDialect(task));
            validateExistingTablePhysicalContract(task, tgt, tgtTable, columns);
            log.debug("DorisTableEnsurer: target table already exists and columns validated. db={} table={}",
                    tgt.getDbName(), tgtTable);
            return;
        }
        List<ColumnInfo> columns = loadSourceColumns(task, srcTable);
        if (columns.isEmpty()) {
            throw new IllegalStateException("源表字段为空，无法生成 DDL: " + srcTable);
        }
        String ddl = ddlBuilder.buildCreateTable(task, tgt, tgtTable, columns, sourceDialect(task));
        log.info("DorisTableEnsurer: creating target table\n{}", ddl);
        // CREATE TABLE IF NOT EXISTS 保证幂等性。
        // 并发场景下先到者建表成功，后到者 IF NOT EXISTS 跳过。
        // 两者都不会再走已存在表校验路径（因为 tableExists 在各自的调用时返回 false）。
        // 这是可接受的行为：首次建表后的第一次执行不做类型校验，后续执行会走已存在表路径。
        executeDdl(tgt, ddl);
    }

    private String sourceDialect(SyncTask task) {
        if (task == null || task.getSourceDataSourceId() == null) {
            return null;
        }
        try {
            var source = sourceDataSourceService.findById(task.getSourceDataSourceId());
            return source == null ? null : source.getType();
        } catch (Exception e) {
            log.debug("DorisTableEnsurer: source dialect lookup failed taskId={} datasourceId={}: {}",
                    task.getId(), task.getSourceDataSourceId(), e.getMessage());
            return null;
        }
    }

    private String resolveTargetTableName(SyncTask task, String srcTable) {
        // 目标表名强制小写，避免 Oracle/SQLServer 大写表名与 Doris 小写列名不匹配
        Map<String, String> targetMap = parseJsonMap(task.getTargetTableMap());
        return targetMap.getOrDefault(srcTable, srcTable).toLowerCase(Locale.ROOT);
    }

    private List<ColumnInfo> loadSourceColumns(SyncTask task, String srcTable) {
        if ("CUSTOM_SQL".equalsIgnoreCase(task.getSourceMode())) {
            return sourceDataSourceService.listCustomSqlColumns(task.getSourceDataSourceId(), task.getCustomSql());
        }
        var source = sourceDataSourceService.findById(task.getSourceDataSourceId());
        String schema = SourceSchemaResolver.resolveRequired(task, source);
        List<ColumnInfo> columns = sourceDataSourceService.listColumns(task.getSourceDataSourceId(), schema, srcTable);
        return columns == null ? List.of() : columns;
    }

    private Map<String, String> parseJsonMap(String json) {
        return TargetTableMapParser.parseStrict(json, objectMapper, null);
    }

    private boolean tableExists(TargetDataSource tgt, String table) throws Exception {
        try (Connection conn = openConnection(tgt);
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(
                     "SELECT 1 FROM information_schema.tables WHERE table_schema='" + escape(tgt.getDbName())
                             + "' AND table_name='" + escape(table) + "' LIMIT 1")) {
            return rs.next();
        }
    }

    private void validateExistingTableColumns(
            TargetDataSource tgt,
            String table,
            List<ColumnInfo> sourceColumns,
            String sourceDialect)
            throws Exception {
        Set<String> expected = expectedDorisColumns(sourceColumns);
        Map<String, TargetColumnMeta> actual = loadTargetColumns(tgt, table);
        List<String> missing = new ArrayList<>();
        for (String column : expected) {
            if (!actual.containsKey(column)) {
                missing.add(column);
            }
        }
        if (!missing.isEmpty()) {
            // 自动补齐缺失字段（ALTER TABLE ADD COLUMN），而不是报错
            log.warn("DorisTableEnsurer: table={}.{} 缺失字段 {}，尝试自动补齐",
                    tgt.getDbName(), table, missing);
            try (Connection alterConn = openConnection(tgt);
                 Statement alterStmt = alterConn.createStatement()) {
                // 注入防御：dbName / table 拼到反引号 DDL 前必须经字符集白名单
                String dbName = IdentifierSanitizer.requireValid(tgt.getDbName(), "tgt.dbName");
                String safeTable = IdentifierSanitizer.requireValid(table, "tgtTable");
                List<String> failedCols = new ArrayList<>();
                for (String col : missing) {
                    // ETL 系统字段用已知类型，其他字段默认 VARCHAR(256) NULL
                    String colType = resolveColumnTypeForAdd(col, sourceColumns);
                    String safeCol = IdentifierSanitizer.requireValid(col, "missingColumn");
                    // 使用 IF NOT EXISTS 让 ALTER 幂等（Doris 2.0+ 支持），避免 partial-applied 状态
                    String alterSql = "ALTER TABLE `" + dbName + "`.`" + safeTable
                            + "` ADD COLUMN IF NOT EXISTS `" + safeCol + "` " + colType + " NULL";
                    try {
                        alterStmt.execute(alterSql);
                        log.info("DorisTableEnsurer: ALTER TABLE ADD COLUMN `{}` {} 成功", safeCol, colType);
                    } catch (Exception e) {
                        log.warn("DorisTableEnsurer: ALTER TABLE ADD COLUMN `{}` 失败: {}", safeCol, e.getMessage());
                        failedCols.add(safeCol + "(" + e.getMessage() + ")");
                    }
                }
                if (!failedCols.isEmpty()) {
                    throw new IllegalStateException("Doris 目标表 " + safeTable + " 自动补列失败: " + failedCols
                            + "，请手动执行 ALTER TABLE 或检查 Doris 状态");
                }
            }
        }
        // 类型兼容性校验：根据全局配置决定是否执行
        if (isTypeCheckEnabled()) {
            validateCompatibleColumnTypes(tgt, table, sourceColumns, actual, sourceDialect);
        } else {
            log.debug("DorisTableEnsurer: 类型兼容性校验已跳过（doris.auto_create.validate_existing.type_check=false）");
        }
    }

    /** 读取全局配置：是否校验已存在表的类型兼容性 */
    private boolean isTypeCheckEnabled() {
        if (systemSettingRepository == null) return true; // 未注入时保持原有行为
        return systemSettingRepository.findById("doris.auto_create.validate_existing.type_check")
                .map(s -> "true".equalsIgnoreCase(s.getSettingValue()))
                .orElse(false); // 默认 false（不校验）
    }

    /** 读取全局配置：是否校验已存在表的 NOT NULL 一致性 */
    private boolean isNullableCheckEnabled() {
        if (systemSettingRepository == null) return false; // 未注入时默认不校验
        return systemSettingRepository.findById("doris.auto_create.validate_existing.nullable_check")
                .map(s -> "true".equalsIgnoreCase(s.getSettingValue()))
                .orElse(false); // 默认 false（不校验）
    }

    /**
     * 判断任务是否由医共体规范驱动创建（spec 069）。
     */
    private boolean isMedicalRegistryTask(SyncTask task) {
        String dc = task.getDataCharacteristics();
        return dc != null && dc.contains("MEDICAL_REGISTRY");
    }

    private String medicalDatasetCode(SyncTask task) {
        String dc = task == null ? null : task.getDataCharacteristics();
        if (dc == null || dc.isBlank()) {
            return null;
        }
        try {
            Map<String, Object> values = objectMapper.readValue(dc, new TypeReference<Map<String, Object>>() {});
            Object mode = values.get("medicalMappingMode");
            Object datasetCode = values.get("matchedDatasetCode");
            boolean contractDriven = mode != null && "CONTRACT_DRIVEN".equalsIgnoreCase(mode.toString());
            if (!contractDriven) {
                return null;
            }
            if (datasetCode == null || datasetCode.toString().isBlank()) {
                throw new IllegalStateException("医共体 contract-driven 任务缺少 matchedDatasetCode");
            }
            return datasetCode.toString().trim().toUpperCase(Locale.ROOT);
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            if (dc.contains("CONTRACT_DRIVEN")) {
                throw new IllegalStateException("医共体 contract-driven 任务 dataCharacteristics 不是合法 JSON: "
                        + e.getMessage(), e);
            }
            return null;
        }
    }

    private MedicalDatasetContract loadMedicalContract(SyncTask task, String datasetCode) {
        if (medicalContractService == null) {
            throw new IllegalStateException("医共体契约服务未启用，无法按规范创建 Doris 表: " + datasetCode);
        }
        return MedicalContractSnapshotCodec.resolveForTask(task, medicalContractService, objectMapper);
    }

    private MedicalDorisDdlBuilder requireMedicalDdlBuilder() {
        if (medicalDdlBuilder == null) {
            throw new IllegalStateException("医共体 Doris DDL 构建器未启用，无法按规范创建 Doris 表");
        }
        return medicalDdlBuilder;
    }

    private Map<String, String> enabledEtlFields() {
        Map<String, String> fields = etlSystemFieldsService == null
                ? Map.of()
                : etlSystemFieldsService.enabledFields();
        return fields == null ? Map.of() : fields;
    }

    private void validateExistingMedicalContractTable(
            TargetDataSource tgt,
            String table,
            MedicalDatasetContract contract) throws Exception {
        Map<String, TargetColumnMeta> actual = loadTargetColumns(tgt, table);
        Set<String> expected = expectedMedicalDorisColumns(contract);
        List<String> missing = new ArrayList<>();
        for (String column : expected) {
            if (!actual.containsKey(column)) {
                missing.add(column);
            }
        }
        if (!missing.isEmpty()) {
            throw new IllegalStateException("Doris 医共体目标表契约不匹配: table="
                    + tgt.getDbName() + "." + table + " 缺失字段=" + missing);
        }

        String createTable = loadShowCreateTable(tgt, table);
        String lower = createTable == null ? "" : createTable.toLowerCase(Locale.ROOT);
        boolean hasPrimaryKey = contract.primaryKeys() != null && !contract.primaryKeys().isEmpty();
        String expectedModel = hasPrimaryKey ? "UNIQUE KEY" : "DUPLICATE KEY";
        if (!lower.contains(expectedModel.toLowerCase(Locale.ROOT))) {
            throw new IllegalStateException("Doris 医共体目标表契约不匹配: table="
                    + tgt.getDbName() + "." + table + " 不是 " + expectedModel + " 表");
        }
        List<String> expectedKeys = expectedMedicalUniqueKeys(contract);
        List<String> actualKeys = hasPrimaryKey
                ? parseUniqueKeyColumns(createTable)
                : parseDuplicateKeyColumns(createTable);
        if (actualKeys.isEmpty()) {
            throw new IllegalStateException("Doris 医共体目标表契约不匹配: table="
                    + tgt.getDbName() + "." + table + " 无法解析 " + expectedModel + " 列");
        }
        if (!actualKeys.equals(expectedKeys)) {
            throw new IllegalStateException("Doris 医共体目标表契约不匹配: table="
                    + tgt.getDbName() + "." + table
                    + " " + expectedModel + " 列不匹配，期望=" + expectedKeys + "，实际=" + actualKeys);
        }

        validateMedicalFieldTypes(tgt, table, contract, actual);

        if (!hasPrimaryKey) {
            return;
        }
        if (!hasDorisPropertyValue(createTable, "enable_unique_key_merge_on_write", "true")) {
            throw new IllegalStateException("Doris 医共体目标表契约不匹配: table="
                    + tgt.getDbName() + "." + table
                    + " 缺少 enable_unique_key_merge_on_write=true，无法安全执行规范主键 Upsert");
        }
        if (contract.incrementalField() != null && !contract.incrementalField().isBlank()) {
            String expectedSequence = contract.incrementalField().toLowerCase(Locale.ROOT);
            if (!hasDorisPropertyValue(createTable, "function_column.sequence_col", expectedSequence)) {
                throw new IllegalStateException("Doris 医共体目标表契约不匹配: table="
                        + tgt.getDbName() + "." + table
                        + " 缺少 function_column.sequence_col=" + expectedSequence);
            }
        }
    }

    private Set<String> expectedMedicalDorisColumns(MedicalDatasetContract contract) {
        LinkedHashSet<String> expected = new LinkedHashSet<>();
        if (contract != null && contract.fields() != null) {
            for (MedicalFieldContract field : contract.fields()) {
                if (field.dorisColumn() != null && !field.dorisColumn().isBlank()) {
                    expected.add(field.dorisColumn().toLowerCase(Locale.ROOT));
                }
            }
        }
        for (String column : enabledEtlFields().keySet()) {
            if (column != null && !column.isBlank()) {
                expected.add(column.toLowerCase(Locale.ROOT));
            }
        }
        return expected;
    }

    private List<String> expectedMedicalUniqueKeys(MedicalDatasetContract contract) {
        List<String> keys = (contract.primaryKeys() == null ? List.<String>of() : contract.primaryKeys()).stream()
                .map(name -> name.toLowerCase(Locale.ROOT))
                .collect(java.util.stream.Collectors.toCollection(ArrayList::new));
        if (enabledEtlFields().keySet().stream().anyMatch(MedicalDorisDdlBuilder.ETL_JOB_ID_COL::equalsIgnoreCase)) {
            keys.add(MedicalDorisDdlBuilder.ETL_JOB_ID_COL);
        }
        return keys;
    }

    private void validateMedicalFieldTypes(
            TargetDataSource tgt,
            String table,
            MedicalDatasetContract contract,
            Map<String, TargetColumnMeta> actual) {
        List<String> incompatible = new ArrayList<>();
        for (MedicalFieldContract field : contract.fields()) {
            TargetColumnMeta actualMeta = actual.get(field.dorisColumn().toLowerCase(Locale.ROOT));
            if (actualMeta == null || actualMeta.dataType() == null) {
                continue;
            }
            MedicalCompatibility compatibility = checkMedicalWritableCompatibility(
                    field,
                    actualMeta.dataType(),
                    actualMeta.characterMaximumLength(),
                    actualMeta.numericPrecision(),
                    actualMeta.numericScale(),
                    actualMeta.datetimePrecision(),
                    actualMeta.nullable());
            if (compatibility.level() == MedicalCompatibilityLevel.FAIL) {
                incompatible.add(field.dorisColumn() + "(期望 " + field.dorisType()
                        + (field.primaryKey() || field.notNull() ? " NOT NULL" : " NULL")
                        + "，实际 " + actualMeta.displayType()
                        + (Boolean.FALSE.equals(actualMeta.nullable()) ? " NOT NULL" : " NULL")
                        + "，原因 " + compatibility.reason() + ")");
            } else if (compatibility.level() == MedicalCompatibilityLevel.WARN) {
                log.warn("DorisTableEnsurer: medical column compatibility warning "
                                + "table={}.{} column={} expected={} actual={} reason={}",
                        tgt.getDbName(), table, field.dorisColumn(), field.dorisType(),
                        actualMeta.displayType(), compatibility.reason());
            }
        }
        if (!incompatible.isEmpty()) {
            throw new IllegalStateException("Doris 医共体目标表字段不可写兼容: table="
                    + tgt.getDbName() + "." + table + "，字段=" + incompatible);
        }
    }

    public static MedicalCompatibility checkMedicalWritableCompatibility(
            MedicalFieldContract expected,
            String actualType,
            Integer actualLength,
            Integer actualPrecision,
            Integer actualScale,
            Boolean actualNullable) {
        return checkMedicalWritableCompatibility(
                expected, actualType, actualLength, actualPrecision, actualScale, null, actualNullable);
    }

    public static MedicalCompatibility checkMedicalWritableCompatibility(
            MedicalFieldContract expected,
            String actualType,
            Integer actualLength,
            Integer actualPrecision,
            Integer actualScale,
            Integer actualDatetimePrecision,
            Boolean actualNullable) {
        if (expected == null || expected.dorisType() == null || actualType == null) {
            return new MedicalCompatibility(
                    MedicalCompatibilityLevel.FAIL, "缺少期望或实际类型元数据");
        }
        String expectedBase = baseType(expected.dorisType());
        String actualBase = baseType(actualType);
        MedicalCompatibility typeCompatibility = checkMedicalTypeCapacity(
                expected.dorisType(), expectedBase, actualBase,
                actualLength, actualPrecision, actualScale, actualDatetimePrecision, expected.primaryKey());
        MedicalCompatibility nullableCompatibility;
        boolean expectedNotNull = expected.primaryKey() || expected.notNull();
        if (actualNullable == null) {
            nullableCompatibility = new MedicalCompatibility(
                    MedicalCompatibilityLevel.WARN, "无法确认 Doris 可空性");
        } else if (!expectedNotNull && !actualNullable) {
            nullableCompatibility = new MedicalCompatibility(
                    MedicalCompatibilityLevel.FAIL, "采集字段允许 NULL，但 Doris 为 NOT NULL");
        } else if (expectedNotNull && actualNullable) {
            nullableCompatibility = new MedicalCompatibility(
                    MedicalCompatibilityLevel.FAIL,
                    expected.primaryKey() ? "主键字段必须为 NOT NULL" : "医共体必填字段必须为 NOT NULL");
        } else {
            nullableCompatibility = new MedicalCompatibility(
                    MedicalCompatibilityLevel.COMPATIBLE, "可空性兼容");
        }
        return combineCompatibility(typeCompatibility, nullableCompatibility);
    }

    private static MedicalCompatibility checkMedicalTypeCapacity(
            String expectedType,
            String expectedBase,
            String actualBase,
            Integer actualLength,
            Integer actualPrecision,
            Integer actualScale,
            Integer actualDatetimePrecision,
            boolean primaryKey) {
        if ("VARCHAR".equals(expectedBase) || "CHAR".equals(expectedBase)) {
            if ("STRING".equals(actualBase) || "TEXT".equals(actualBase)) {
                if (!primaryKey) {
                    return new MedicalCompatibility(
                            MedicalCompatibilityLevel.WARN, "普通采集字段按字符串兼容");
                }
                return new MedicalCompatibility(
                        MedicalCompatibilityLevel.WARN, "Doris STRING 容量大于定长字符串定义");
            }
            if (!"VARCHAR".equals(actualBase) && !"CHAR".equals(actualBase)) {
                return incompatibleBase(expectedBase, actualBase);
            }
            Integer expectedLength = varcharLength(expectedType);
            if (expectedLength != null && actualLength != null && actualLength < expectedLength) {
                return new MedicalCompatibility(
                        MedicalCompatibilityLevel.FAIL,
                        "Doris 字符长度 " + actualLength + " 小于需要的 " + expectedLength);
            }
            if (expectedLength == null || actualLength == null) {
                return new MedicalCompatibility(
                        MedicalCompatibilityLevel.WARN, "无法完整确认 Doris 字符长度");
            }
            if (actualLength > expectedLength || !expectedBase.equals(actualBase)) {
                return new MedicalCompatibility(
                        MedicalCompatibilityLevel.WARN, "Doris 字符容量更宽");
            }
            return compatible();
        }
        if ("STRING".equals(expectedBase) || "TEXT".equals(expectedBase)) {
            if ("STRING".equals(actualBase) || "TEXT".equals(actualBase)
                    || (!primaryKey && ("VARCHAR".equals(actualBase) || "CHAR".equals(actualBase)))) {
                return compatible();
            }
            if ("VARCHAR".equals(actualBase)
                    && actualLength != null
                    && actualLength >= DorisTypeMappingPolicy.DORIS_STRING_METADATA_MIN_LENGTH) {
                return compatible();
            }
            return new MedicalCompatibility(
                    MedicalCompatibilityLevel.FAIL, "无界 STRING 不能写入有界 " + actualBase);
        }
        if (isMedicalDecimalBase(expectedBase)) {
            if (!isMedicalDecimalBase(actualBase)) {
                return incompatibleBase(expectedBase, actualBase);
            }
            int[] expectedDecimal = decimalPrecisionScale(expectedType);
            if (expectedDecimal == null || actualPrecision == null || actualScale == null) {
                return new MedicalCompatibility(
                        MedicalCompatibilityLevel.WARN, "无法完整确认 Doris DECIMAL 容量");
            }
            int expectedIntegerDigits = expectedDecimal[0] - expectedDecimal[1];
            int actualIntegerDigits = actualPrecision - actualScale;
            if (actualScale < expectedDecimal[1] || actualIntegerDigits < expectedIntegerDigits) {
                return new MedicalCompatibility(
                        MedicalCompatibilityLevel.FAIL,
                        "Doris DECIMAL 整数位或小数位不足");
            }
            if (actualScale > expectedDecimal[1] || actualIntegerDigits > expectedIntegerDigits) {
                return new MedicalCompatibility(
                        MedicalCompatibilityLevel.WARN, "Doris DECIMAL 容量更宽");
            }
            return compatible();
        }
        if ("DATE".equals(expectedBase) && "DATETIME".equals(actualBase)) {
            return new MedicalCompatibility(
                    MedicalCompatibilityLevel.WARN, "Doris DATETIME 可保存 DATE");
        }
        if ("DATETIME".equals(expectedBase) && "DATE".equals(actualBase)) {
            return new MedicalCompatibility(
                    MedicalCompatibilityLevel.FAIL, "Doris DATE 会丢失时间部分");
        }
        if ("DATETIME".equals(expectedBase) && "DATETIME".equals(actualBase)) {
            Integer expectedDatetimePrecision = datetimePrecision(expectedType);
            if (expectedDatetimePrecision == null) {
                return compatible();
            }
            if (actualDatetimePrecision == null) {
                return new MedicalCompatibility(
                        MedicalCompatibilityLevel.FAIL,
                        "无法确认 Doris DATETIME 精度，期望 DATETIME(" + expectedDatetimePrecision + ")");
            }
            if (actualDatetimePrecision < expectedDatetimePrecision) {
                return new MedicalCompatibility(
                        MedicalCompatibilityLevel.FAIL,
                        "Doris DATETIME(" + actualDatetimePrecision + ") 无法保存 "
                                + expectedDatetimePrecision + " 位小数秒");
            }
            if (actualDatetimePrecision > expectedDatetimePrecision) {
                return new MedicalCompatibility(
                        MedicalCompatibilityLevel.WARN, "Doris DATETIME 小数秒精度更宽");
            }
            return compatible();
        }
        Integer expectedIntegerRank = integerRank(expectedBase);
        Integer actualIntegerRank = integerRank(actualBase);
        if (expectedIntegerRank != null && actualIntegerRank != null) {
            if (actualIntegerRank < expectedIntegerRank) {
                return new MedicalCompatibility(
                        MedicalCompatibilityLevel.FAIL, "Doris 整数类型容量更小");
            }
            if (actualIntegerRank > expectedIntegerRank) {
                return new MedicalCompatibility(
                        MedicalCompatibilityLevel.WARN, "Doris 整数类型容量更宽");
            }
            return compatible();
        }
        return expectedBase.equals(actualBase) ? compatible() : incompatibleBase(expectedBase, actualBase);
    }

    private static MedicalCompatibility combineCompatibility(
            MedicalCompatibility first, MedicalCompatibility second) {
        MedicalCompatibilityLevel level = first.level().ordinal() >= second.level().ordinal()
                ? first.level() : second.level();
        if (first.level() == MedicalCompatibilityLevel.COMPATIBLE) {
            return new MedicalCompatibility(level, second.reason());
        }
        if (second.level() == MedicalCompatibilityLevel.COMPATIBLE) {
            return new MedicalCompatibility(level, first.reason());
        }
        return new MedicalCompatibility(level, first.reason() + "；" + second.reason());
    }

    private static MedicalCompatibility compatible() {
        return new MedicalCompatibility(MedicalCompatibilityLevel.COMPATIBLE, "可写兼容");
    }

    private static MedicalCompatibility incompatibleBase(String expectedBase, String actualBase) {
        return new MedicalCompatibility(
                MedicalCompatibilityLevel.FAIL,
                "基础类型不兼容，期望 " + expectedBase + "，实际 " + actualBase);
    }

    private static Integer integerRank(String base) {
        return switch (base) {
            case "TINYINT" -> 1;
            case "SMALLINT" -> 2;
            case "INT", "INTEGER" -> 3;
            case "BIGINT" -> 4;
            case "LARGEINT" -> 5;
            default -> null;
        };
    }

    private static int[] decimalPrecisionScale(String type) {
        Matcher matcher = Pattern.compile("(?i)(?:DECIMAL|NUMERIC)\\((\\d+)\\s*,\\s*(\\d+)\\)")
                .matcher(type.trim());
        if (!matcher.matches()) {
            return null;
        }
        return new int[]{Integer.parseInt(matcher.group(1)), Integer.parseInt(matcher.group(2))};
    }

    private static boolean isMedicalDecimalBase(String base) {
        return "DECIMAL".equals(base) || "NUMERIC".equals(base);
    }

    private static String baseType(String type) {
        if (type == null) {
            return "";
        }
        int idx = type.indexOf('(');
        return (idx >= 0 ? type.substring(0, idx) : type).trim().toUpperCase(Locale.ROOT);
    }

    private static Integer varcharLength(String type) {
        if (type == null || !type.toUpperCase(Locale.ROOT).startsWith("VARCHAR(")) {
            return null;
        }
        Matcher matcher = Pattern.compile("(?i)VARCHAR\\((\\d+)\\)").matcher(type.trim());
        if (!matcher.matches()) {
            return null;
        }
        return Integer.parseInt(matcher.group(1));
    }

    private static Integer datetimePrecision(String type) {
        if (type == null) {
            return null;
        }
        Matcher matcher = Pattern.compile("(?i)DATETIME\\((\\d+)\\)").matcher(type.trim());
        return matcher.matches() ? Integer.parseInt(matcher.group(1)) : null;
    }

    public enum MedicalCompatibilityLevel {
        COMPATIBLE,
        WARN,
        FAIL
    }

    public record MedicalCompatibility(MedicalCompatibilityLevel level, String reason) { }

    /**
     * 为自动 ADD COLUMN 推断列类型。
     * ETL 系统字段用已知类型，其他字段默认 VARCHAR(256)。
     */
    private String resolveColumnTypeForAdd(String columnName, List<ColumnInfo> sourceColumns) {
        // ETL 系统字段
        Map<String, String> etlTypes = Map.of(
                "_etl_batch_id", "BIGINT",
                "_etl_job_id", "BIGINT",
                "_etl_job_version", "VARCHAR(20)",
                "_etl_sync_time", "DATETIME",
                "_etl_source_system", "VARCHAR(100)",
                "_etl_window_start", "VARCHAR(50)",
                "_etl_window_end", "VARCHAR(50)"
        );
        if (etlTypes.containsKey(columnName)) {
            return etlTypes.get(columnName);
        }
        // 从源端列信息推断
        for (ColumnInfo col : sourceColumns) {
            if (columnName.equalsIgnoreCase(col.columnName())) {
                String dt = col.dataType();
                if (dt != null) {
                    String upper = dt.toUpperCase();
                    if (upper.contains("INT")) return "BIGINT";
                    if (upper.contains("TIMESTAMP") || upper.contains("DATETIME")) return "DATETIME";
                    if (upper.contains("DATE")) return "DATE";
                    if (upper.contains("DECIMAL") || upper.contains("NUMERIC")) return "DECIMAL(18,4)";
                    if (upper.contains("FLOAT") || upper.contains("DOUBLE")) return "DOUBLE";
                }
                Integer size = col.columnSize();
                if (size != null && size > 0) {
                    long bytes = Math.min(65533L, Math.max(1L, size.longValue()) * 3L);
                    return "VARCHAR(" + bytes + ")";
                }
                break;
            }
        }
        return "VARCHAR(256)";
    }

    private void validateExistingTablePhysicalContract(
            SyncTask task,
            TargetDataSource tgt,
            String table,
            List<ColumnInfo> sourceColumns) throws Exception {
        boolean merge = Boolean.TRUE.equals(task.getEnableDorisMerge());
        boolean partialUpdate = Boolean.TRUE.equals(task.getPartialColumns());
        boolean hasSequence = task.getSequenceCol() != null && !task.getSequenceCol().isBlank();
        boolean uniqueLike = merge
                || partialUpdate
                || hasSequence
                || "UPSERT".equalsIgnoreCase(task.getSyncMode())
                || "UNIQUE_KEY".equalsIgnoreCase(task.getDorisTableModel())
                || (task.getUpsertKeys() != null && task.getUpsertKeys().stream()
                .anyMatch(key -> key != null && !key.isBlank()));
        if (!uniqueLike) {
            return;
        }

        String model = task.getDorisTableModel() == null
                ? (uniqueLike ? "UNIQUE_KEY" : "DUPLICATE_KEY")
                : task.getDorisTableModel();
        if (!"UNIQUE_KEY".equalsIgnoreCase(model)) {
            throw new IllegalStateException("Doris MERGE/partial update/sequence_col 仅支持 UNIQUE KEY 表: table="
                    + tgt.getDbName() + "." + table);
        }

        String createTable = loadShowCreateTable(tgt, table);
        String lower = createTable == null ? "" : createTable.toLowerCase(Locale.ROOT);
        if (!lower.contains("unique key")) {
            throw new IllegalStateException("Doris 目标表物理契约不匹配: table="
                    + tgt.getDbName() + "." + table + " 不是 UNIQUE KEY 表");
        }
        validateUniqueKeyColumns(task, tgt, table, sourceColumns, createTable, model);
        if ((merge || partialUpdate)
                && !hasDorisPropertyValue(createTable, "enable_unique_key_merge_on_write", "true")) {
            throw new IllegalStateException("Doris 目标表物理契约不匹配: table="
                    + tgt.getDbName() + "." + table
                    + " 缺少 enable_unique_key_merge_on_write=true，无法安全执行 MERGE/partial update");
        }
        if (hasSequence) {
            String expectedSequence = resolveSourceColumnLower(sourceColumns, task.getSequenceCol(), "sequence_col");
            if (!hasDorisPropertyValue(createTable, "function_column.sequence_col", expectedSequence)) {
                throw new IllegalStateException("Doris 目标表物理契约不匹配: table="
                        + tgt.getDbName() + "." + table
                        + " 缺少 function_column.sequence_col=" + expectedSequence);
            }
        }
    }

    Set<String> expectedDorisColumns(List<ColumnInfo> sourceColumns) {
        LinkedHashSet<String> expected = new LinkedHashSet<>();
        if (sourceColumns == null) {
            return expected;
        }
        for (ColumnInfo column : sourceColumns) {
            if (column == null || column.columnName() == null || column.columnName().isBlank()) {
                continue;
            }
            expected.add(column.columnName().toLowerCase(Locale.ROOT));
        }
        Map<String, String> etlFields = etlSystemFieldsService == null
                ? Map.of()
                : etlSystemFieldsService.enabledFields();
        if (etlFields != null) {
            for (String column : etlFields.keySet()) {
                if (column == null || column.isBlank()) {
                    continue;
                }
                expected.add(column.toLowerCase(Locale.ROOT));
            }
        }
        return expected;
    }

    private Map<String, TargetColumnMeta> loadTargetColumns(TargetDataSource tgt, String table) throws Exception {
        LinkedHashMap<String, TargetColumnMeta> columns = new LinkedHashMap<>();
        try (Connection conn = openConnection(tgt);
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(
                     "SELECT column_name, data_type, character_maximum_length, numeric_precision, numeric_scale, datetime_precision, is_nullable "
                             + "FROM information_schema.columns WHERE table_schema='" + escape(tgt.getDbName())
                             + "' AND table_name='" + escape(table) + "'")) {
            while (rs.next()) {
                String name = rs.getString("column_name");
                if (name != null && !name.isBlank()) {
                    String lower = name.toLowerCase(Locale.ROOT);
                    columns.put(lower, new TargetColumnMeta(
                            lower,
                            rs.getString("data_type"),
                            nullableInteger(rs.getObject("character_maximum_length")),
                            nullableInteger(rs.getObject("numeric_precision")),
                            nullableInteger(rs.getObject("numeric_scale")),
                            nullableInteger(rs.getObject("datetime_precision")),
                            nullableBoolean(rs.getString("is_nullable"))));
                }
            }
        }
        return columns;
    }

    private String loadShowCreateTable(TargetDataSource tgt, String table) throws Exception {
        try (Connection conn = openConnection(tgt);
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SHOW CREATE TABLE `"
                     + escapeIdentifier(tgt.getDbName()) + "`.`" + escapeIdentifier(table) + "`")) {
            if (rs.next()) {
                return rs.getString(2);
            }
        }
        throw new IllegalStateException("无法读取 Doris SHOW CREATE TABLE: " + tgt.getDbName() + "." + table);
    }

    private void executeDdl(TargetDataSource tgt, String ddl) throws Exception {
        try (Connection conn = openConnection(tgt);
             Statement stmt = conn.createStatement()) {
            stmt.execute(ddl);
        }
    }

    protected Connection openConnection(TargetDataSource tgt) throws Exception {
        String url = "jdbc:mysql://" + tgt.getFeHost() + ":" + tgt.getFePort() + "/" + tgt.getDbName()
                + "?useSSL=false&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true";
        return connectionPoolManager.getConnection(url, tgt.getUsername(), aesUtil.decrypt(tgt.getPasswordEnc()));
    }

    /** 简单的标识符转义（白名单字符已在前置校验，此处仅防御 ' 注入）*/
    private String escape(String s) {
        return s == null ? "" : s.replace("'", "''");
    }

    private String escapeIdentifier(String s) {
        return s == null ? "" : s.replace("`", "``");
    }

    private String resolveSourceColumnLower(List<ColumnInfo> cols, String name, String label) {
        String expected = name == null ? "" : name.trim();
        if (expected.isEmpty()) {
            throw new IllegalArgumentException(label + " 不能为空");
        }
        for (ColumnInfo c : cols) {
            if (c.columnName().equalsIgnoreCase(expected)) {
                return c.columnName().toLowerCase(Locale.ROOT);
            }
        }
        throw new IllegalArgumentException(label + " 字段不存在于源字段列表: " + expected);
    }

    private boolean hasDorisPropertyValue(String createTable, String key, String expectedValue) {
        if (createTable == null || key == null || expectedValue == null) {
            return false;
        }
        Pattern property = Pattern.compile(
                "(?is)[\"']" + Pattern.quote(key) + "[\"']\\s*=\\s*[\"']([^\"']+)[\"']");
        Matcher matcher = property.matcher(createTable);
        while (matcher.find()) {
            String actual = matcher.group(1);
            if (expectedValue.equalsIgnoreCase(actual == null ? "" : actual.trim())) {
                return true;
            }
        }
        return false;
    }

    private void validateUniqueKeyColumns(
            SyncTask task,
            TargetDataSource tgt,
            String table,
            List<ColumnInfo> sourceColumns,
            String createTable,
            String model) {
        List<String> expected = keyInspector.inferKeyColumns(model, task, sourceColumns).stream()
                .map(name -> name.toLowerCase(Locale.ROOT))
                .toList();
        Map<String, String> etlFields = etlSystemFieldsService == null
                ? Map.of()
                : etlSystemFieldsService.enabledFields();
        expected = DorisDdlBuilder.includeTenantScopeKey(model, expected, etlFields).stream()
                .map(name -> name.toLowerCase(Locale.ROOT))
                .toList();
        List<String> actual = parseUniqueKeyColumns(createTable);
        if (actual.isEmpty()) {
            throw new IllegalStateException("Doris 目标表物理契约不匹配: table="
                    + tgt.getDbName() + "." + table + " 无法解析 UNIQUE KEY 列");
        }
        if (!actual.equals(expected)) {
            throw new IllegalStateException("Doris 目标表物理契约不匹配: table="
                    + tgt.getDbName() + "." + table
                    + " UNIQUE KEY 列不匹配，期望=" + expected + "，实际=" + actual);
        }
    }

    private List<String> parseUniqueKeyColumns(String createTable) {
        return parseKeyColumns(createTable, "unique");
    }

    private List<String> parseDuplicateKeyColumns(String createTable) {
        return parseKeyColumns(createTable, "duplicate");
    }

    private List<String> parseKeyColumns(String createTable, String model) {
        if (createTable == null || createTable.isBlank()) {
            return List.of();
        }
        Matcher matcher = Pattern.compile("(?is)\\b" + Pattern.quote(model)
                + "\\s+key\\s*\\(([^)]*)\\)").matcher(createTable);
        if (!matcher.find()) {
            return List.of();
        }
        List<String> columns = new ArrayList<>();
        for (String raw : matcher.group(1).split(",")) {
            String col = raw.trim()
                    .replace("`", "")
                    .replace("\"", "")
                    .replace("'", "")
                    .toLowerCase(Locale.ROOT);
            if (!col.isBlank()) {
                columns.add(col);
            }
        }
        return columns;
    }

    private void validateCompatibleColumnTypes(
            TargetDataSource tgt,
            String table,
            List<ColumnInfo> sourceColumns,
            Map<String, TargetColumnMeta> actualColumns,
            String sourceDialect) {
        boolean nullableCheck = isNullableCheckEnabled();
        List<String> incompatible = new ArrayList<>();
        for (ColumnInfo sourceColumn : sourceColumns) {
            if (sourceColumn == null || sourceColumn.columnName() == null || sourceColumn.columnName().isBlank()) {
                continue;
            }
            String columnName = sourceColumn.columnName().toLowerCase(Locale.ROOT);
            TargetColumnMeta actual = actualColumns.get(columnName);
            if (actual == null || actual.dataType() == null || actual.dataType().isBlank()) {
                continue;
            }
            DorisTypeMappingPolicy.SourceTypeDescriptor descriptor =
                    DorisTypeMappingPolicy.SourceTypeDescriptor.fromColumn(sourceDialect, sourceColumn, true);
            DorisTypeMappingPolicy.MappingResult expected = typeMappingRuleService != null
                    ? typeMappingRuleService.recommend(descriptor)
                    : typeMappingPolicy.recommend(descriptor);
            DorisTypeMappingPolicy.CompatibilityResult result =
                    typeMappingPolicy.checkCompatible(expected, descriptor, actual.toDorisDescriptor());
            if (result.compatibilityLevel() == DorisTypeMappingPolicy.CompatibilityLevel.FAIL) {
                // 如果 nullable_check 关闭，跳过 nullable 不匹配的报错
                if (!nullableCheck && result.reason() != null
                        && result.reason().contains("NULL")) {
                    log.debug("DorisTableEnsurer: 跳过 nullable 不兼容（nullable_check=false）: table={}.{} column={}",
                            tgt.getDbName(), table, columnName);
                    continue;
                }
                incompatible.add(columnName + "(期望 " + result.expectedDorisType()
                        + "，实际 " + result.actualDorisType()
                        + "，原因 " + result.reason() + ")");
            } else if (result.compatibilityLevel() == DorisTypeMappingPolicy.CompatibilityLevel.WARN) {
                log.warn("DorisTableEnsurer: column type warning table={}.{} column={} expected={} actual={} reason={}",
                        tgt.getDbName(), table, columnName,
                        result.expectedDorisType(), result.actualDorisType(), result.reason());
            }
        }
        if (!incompatible.isEmpty()) {
            throw new IllegalStateException("Doris 目标表字段类型不兼容: table="
                    + tgt.getDbName() + "." + table + "，字段=" + incompatible);
        }
    }

    private Integer nullableInteger(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            return number.intValue();
        }
        String text = value.toString();
        if (text.isBlank()) {
            return null;
        }
        return Integer.parseInt(text);
    }

    private Boolean nullableBoolean(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.trim().toUpperCase(Locale.ROOT);
        if ("YES".equals(normalized) || "TRUE".equals(normalized) || "1".equals(normalized)) {
            return true;
        }
        if ("NO".equals(normalized) || "FALSE".equals(normalized) || "0".equals(normalized)) {
            return false;
        }
        return null;
    }

    private record TargetColumnMeta(
            String name,
            String dataType,
            Integer characterMaximumLength,
            Integer numericPrecision,
            Integer numericScale,
            Integer datetimePrecision,
            Boolean nullable) {

        DorisTypeMappingPolicy.DorisColumnDescriptor toDorisDescriptor() {
            return new DorisTypeMappingPolicy.DorisColumnDescriptor(
                    dataType, characterMaximumLength, numericPrecision, numericScale, datetimePrecision, nullable);
        }

        String displayType() {
            String base = dataType == null ? "" : dataType.toUpperCase(Locale.ROOT);
            if (characterMaximumLength != null && isTextBase(base)) {
                return base + "(" + characterMaximumLength + ")";
            }
            if (numericPrecision != null && isDecimalBase(base)) {
                int scale = numericScale == null ? 0 : numericScale;
                return base + "(" + numericPrecision + "," + scale + ")";
            }
            if (datetimePrecision != null && "DATETIME".equals(base)) {
                return base + "(" + datetimePrecision + ")";
            }
            return dataType;
        }

        private boolean isTextBase(String base) {
            return "CHAR".equals(base) || "VARCHAR".equals(base);
        }

        private boolean isDecimalBase(String base) {
            return "DECIMAL".equals(base) || "NUMERIC".equals(base);
        }
    }
}
