package com.dfygt.dfetl.server.engine.doris;

import com.dfygt.dfetl.server.service.SourceDataSourceService.ColumnInfo;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Centralized source JDBC type -> Doris type policy.
 *
 * <p>医疗视图接入的核心约束是：自动建表和已存在表校验必须使用同一套类型规则。
 * 这里同时返回推荐 Doris 类型和兼容性等级；WARN 表示可以建表/写入但存在语义损失，
 * FAIL 表示已存在表容量或类型无法安全承载源端字段。</p>
 */
@Component
public class DorisTypeMappingPolicy {

    static final int DORIS_STRING_METADATA_MIN_LENGTH = 1_000_000_000;

    public enum CompatibilityLevel {
        PASS,
        WARN,
        FAIL
    }

    public record SourceTypeDescriptor(
            String sourceDialect,
            String sourceTypeName,
            Integer jdbcType,
            Integer precision,
            Integer scale,
            Integer length,
            boolean nullable,
            String columnName,
            boolean viewColumn) {

        public static SourceTypeDescriptor fromColumn(String sourceDialect, ColumnInfo column, boolean viewColumn) {
            return new SourceTypeDescriptor(
                    sourceDialect,
                    column == null ? null : column.dataType(),
                    column == null ? null : column.jdbcType(),
                    column == null ? null : column.columnSize(),
                    column == null ? null : column.decimalDigits(),
                    column == null ? null : column.columnSize(),
                    column != null && column.nullable(),
                    column == null ? null : column.columnName(),
                    viewColumn);
        }
    }

    public record DorisColumnDescriptor(
            String dataType,
            Integer characterMaximumLength,
            Integer numericPrecision,
            Integer numericScale,
            Integer datetimePrecision,
            Boolean nullable) {

        String displayType() {
            String base = dataType == null ? "" : normalizeBase(dataType);
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
    }

    public record MappingResult(
            String recommendedDorisType,
            CompatibilityLevel compatibilityLevel,
            String reason,
            NormalizedSourceType normalized) {
    }

    public record CompatibilityResult(
            CompatibilityLevel compatibilityLevel,
            String reason,
            String expectedDorisType,
            String actualDorisType) {
    }

    public record NormalizedSourceType(
            String dialect,
            String baseType,
            boolean unsigned,
            boolean timezoneAware,
            boolean binary,
            boolean lob,
            boolean complex,
            boolean unbounded) {
    }

    public MappingResult recommend(SourceTypeDescriptor source) {
        NormalizedSourceType normalized = normalizeSource(source);
        String dialect = normalized.dialect();
        String base = normalized.baseType();
        CompatibilityLevel level = CompatibilityLevel.PASS;
        String reason = "";

        MappingDecision decision;
        switch (dialect) {
            case "ORACLE" -> decision = mapOracle(source, normalized);
            case "POSTGRESQL", "POSTGRES" -> decision = mapPostgresql(source, normalized);
            case "SQLSERVER", "SQL_SERVER", "MSSQL" -> decision = mapSqlServer(source, normalized);
            case "MYSQL" -> decision = mapMysql(source, normalized);
            default -> decision = mapGeneric(source, normalized);
        }

        if (decision.level() != CompatibilityLevel.PASS) {
            level = decision.level();
            reason = decision.reason();
        }
        return new MappingResult(decision.dorisType(), level, reason, normalized);
    }

    public CompatibilityResult checkCompatible(SourceTypeDescriptor source, DorisColumnDescriptor target) {
        MappingResult expected = recommend(source);
        return checkCompatible(expected, source, target);
    }

    public CompatibilityResult checkCompatible(MappingResult expected, SourceTypeDescriptor source, DorisColumnDescriptor target) {
        TypeSpec expectedSpec = parseTypeSpec(expected.recommendedDorisType(), null, null, null, null);
        TypeSpec actualSpec = parseTypeSpec(
                target.dataType(),
                target.characterMaximumLength(),
                target.numericPrecision(),
                target.numericScale(),
                target.datetimePrecision());

        if (Boolean.TRUE.equals(source.nullable()) && Boolean.FALSE.equals(target.nullable())) {
            return fail(expected, target, "源字段允许 NULL，但 Doris 目标字段为 NOT NULL");
        }
        if (!isCompatible(expectedSpec, actualSpec)) {
            return fail(expected, target, "Doris 目标字段类型或容量不足");
        }
        CompatibilityLevel level = expected.compatibilityLevel();
        return new CompatibilityResult(level, expected.reason(), expected.recommendedDorisType(), target.displayType());
    }

    private CompatibilityResult fail(MappingResult expected, DorisColumnDescriptor target, String reason) {
        return new CompatibilityResult(
                CompatibilityLevel.FAIL,
                reason,
                expected.recommendedDorisType(),
                target.displayType());
    }

    private MappingDecision mapMysql(SourceTypeDescriptor source, NormalizedSourceType normalized) {
        String base = normalized.baseType();
        return switch (base) {
            case "BOOL", "BOOLEAN" -> pass("BOOLEAN");
            case "TINYINT" -> {
                if (normalized.unsigned()) yield warn("SMALLINT", "MySQL TINYINT UNSIGNED 超出 Doris TINYINT 有符号范围");
                if (Integer.valueOf(1).equals(source.length())) {
                    yield warn("TINYINT", "MySQL TINYINT(1) 在医疗系统中可能是数值标志，默认不映射 BOOLEAN");
                }
                yield pass("TINYINT");
            }
            case "SMALLINT" -> normalized.unsigned()
                    ? warn("INT", "MySQL SMALLINT UNSIGNED 超出 Doris SMALLINT 有符号范围")
                    : pass("SMALLINT");
            case "MEDIUMINT" -> pass("INT");
            case "INT", "INTEGER" -> normalized.unsigned()
                    ? warn("BIGINT", "MySQL INT UNSIGNED 超出 Doris INT 有符号范围")
                    : pass("INT");
            case "BIGINT" -> normalized.unsigned()
                    ? warn("DECIMAL(20,0)", "MySQL BIGINT UNSIGNED 超出 Doris BIGINT 有符号范围")
                    : pass("BIGINT");
            case "FLOAT" -> pass("FLOAT");
            case "DOUBLE", "DOUBLE PRECISION" -> pass("DOUBLE");
            case "DECIMAL", "NUMERIC" -> decimalOrDefault(source, "MySQL DECIMAL/NUMERIC 缺少 precision/scale，使用 DECIMAL(38,10)");
            case "CHAR" -> pass(charType("CHAR", source.length()));
            case "VARCHAR" -> pass(varcharType(source.length()));
            case "TINYTEXT", "TEXT", "MEDIUMTEXT", "LONGTEXT" -> pass("STRING");
            case "ENUM", "SET" -> warn("STRING", "MySQL ENUM/SET 映射为 STRING，枚举约束不会在 Doris 中保留");
            case "DATE" -> pass("DATE");
            case "DATETIME" -> pass("DATETIME");
            case "TIMESTAMP" -> pass("DATETIME");
            case "TIME" -> warn("STRING", "Doris TIME 兼容性未作为当前平台契约，MySQL TIME 映射为 STRING");
            case "YEAR" -> warn("SMALLINT", "MySQL YEAR 映射为 SMALLINT");
            case "BINARY", "VARBINARY", "TINYBLOB", "BLOB", "MEDIUMBLOB", "LONGBLOB" -> warn("STRING", "MySQL 二进制/LOB 字段映射为 STRING");
            case "JSON" -> warn("STRING", "MySQL JSON 映射为 STRING，JSON 类型约束不会在 Doris 中保留");
            default -> mapGeneric(source, normalized);
        };
    }

    private MappingDecision mapOracle(SourceTypeDescriptor source, NormalizedSourceType normalized) {
        String base = normalized.baseType();
        return switch (base) {
            case "NUMBER", "NUMERIC", "DECIMAL" -> oracleNumber(source);
            case "FLOAT", "BINARY_FLOAT" -> pass("FLOAT");
            case "BINARY_DOUBLE", "DOUBLE" -> pass("DOUBLE");
            case "CHAR" -> pass(charType("CHAR", source.length()));
            case "NCHAR", "VARCHAR2", "NVARCHAR2", "VARCHAR" -> pass(varcharType(source.length()));
            case "CLOB", "NCLOB", "LONG", "XMLTYPE" -> warn("STRING", "Oracle 大文本/结构化字段映射为 STRING");
            case "DATE" -> pass("DATETIME");
            case "TIMESTAMP" -> pass("DATETIME");
            case "TIMESTAMP WITH TIME ZONE", "TIMESTAMP WITH LOCAL TIME ZONE" -> pass("DATETIME");
            case "INTERVAL YEAR TO MONTH", "INTERVAL DAY TO SECOND" -> warn("STRING", "Oracle INTERVAL 映射为 STRING");
            case "RAW", "LONG RAW", "BLOB", "BFILE" -> warn("STRING", "Oracle 二进制/外部文件字段映射为 STRING");
            case "ROWID", "UROWID" -> pass("STRING");
            default -> mapGeneric(source, normalized);
        };
    }

    private MappingDecision mapPostgresql(SourceTypeDescriptor source, NormalizedSourceType normalized) {
        String base = normalized.baseType();
        return switch (base) {
            case "SMALLINT", "INT2" -> pass("SMALLINT");
            case "INTEGER", "INT", "INT4", "SERIAL", "SERIAL4" -> pass("INT");
            case "BIGINT", "INT8", "BIGSERIAL", "SERIAL8" -> pass("BIGINT");
            case "NUMERIC", "DECIMAL" -> decimalOrDefault(source, "PostgreSQL numeric/decimal 缺少 precision/scale，使用 DECIMAL(38,10)");
            case "REAL", "FLOAT4" -> pass("FLOAT");
            case "DOUBLE PRECISION", "FLOAT8" -> pass("DOUBLE");
            case "MONEY" -> warn("DECIMAL(19,4)", "PostgreSQL money 映射为 DECIMAL(19,4)");
            case "CHAR", "CHARACTER" -> pass(charType("CHAR", source.length()));
            case "VARCHAR", "CHARACTER VARYING" -> pass(varcharType(source.length()));
            case "TEXT" -> pass("STRING");
            case "CITEXT" -> warn("STRING", "PostgreSQL citext 大小写不敏感语义映射到 Doris 后不保留");
            case "DATE" -> pass("DATE");
            case "TIMESTAMP", "TIMESTAMP WITHOUT TIME ZONE" -> pass("DATETIME");
            case "TIMESTAMPTZ", "TIMESTAMP WITH TIME ZONE" -> pass("DATETIME");
            case "TIME", "TIME WITHOUT TIME ZONE", "TIME WITH TIME ZONE", "INTERVAL" ->
                    warn("STRING", "PostgreSQL time/interval 映射为 STRING");
            case "BOOLEAN", "BOOL" -> pass("BOOLEAN");
            case "UUID", "INET", "CIDR", "MACADDR", "MACADDR8" -> pass("STRING");
            case "JSON", "JSONB" -> warn("STRING", "PostgreSQL JSON/JSONB 映射为 STRING，JSON 类型约束不会在 Doris 中保留");
            case "BYTEA" -> warn("STRING", "PostgreSQL BYTEA 映射为 STRING");
            case "ARRAY", "HSTORE" -> warn("STRING", "PostgreSQL 复杂类型映射为 STRING");
            default -> {
                if (base.endsWith("[]")) yield warn("STRING", "PostgreSQL 数组类型映射为 STRING");
                yield mapGeneric(source, normalized);
            }
        };
    }

    private MappingDecision mapSqlServer(SourceTypeDescriptor source, NormalizedSourceType normalized) {
        String base = normalized.baseType();
        return switch (base) {
            case "BIT" -> pass("BOOLEAN");
            case "TINYINT" -> warn("SMALLINT", "SQL Server tinyint 为 0-255，无符号，映射为 SMALLINT");
            case "SMALLINT" -> pass("SMALLINT");
            case "INT", "INTEGER" -> pass("INT");
            case "BIGINT" -> pass("BIGINT");
            case "DECIMAL", "NUMERIC" -> decimalOrDefault(source, "SQL Server decimal/numeric 缺少 precision/scale，使用 DECIMAL(38,10)");
            case "MONEY" -> pass("DECIMAL(19,4)");
            case "SMALLMONEY" -> pass("DECIMAL(10,4)");
            case "FLOAT" -> pass("DOUBLE");
            case "REAL" -> pass("FLOAT");
            case "CHAR" -> pass(charType("CHAR", source.length()));
            case "VARCHAR" -> pass(sqlServerString(source, false));
            case "NCHAR" -> warn(varcharType(source.length()), "SQL Server Unicode 定长字符映射为 Doris VARCHAR，字符集语义不保留");
            case "NVARCHAR" -> warn(sqlServerString(source, true), "SQL Server Unicode 字符映射为 Doris VARCHAR/STRING，字符集语义不保留");
            case "TEXT", "NTEXT" -> warn("STRING", "SQL Server text/ntext 映射为 STRING");
            case "DATE" -> pass("DATE");
            case "DATETIME", "DATETIME2", "SMALLDATETIME" -> pass("DATETIME");
            case "DATETIMEOFFSET" -> pass("DATETIME");
            case "TIME" -> warn("STRING", "SQL Server time 映射为 STRING");
            case "TIMESTAMP", "ROWVERSION" -> warn("STRING", "SQL Server timestamp/rowversion 不是时间类型，映射为 STRING");
            case "BINARY", "VARBINARY", "IMAGE" -> warn("STRING", "SQL Server 二进制字段映射为 STRING");
            case "UNIQUEIDENTIFIER", "XML", "HIERARCHYID", "GEOGRAPHY", "GEOMETRY" -> pass("STRING");
            case "SQL_VARIANT" -> warn("STRING", "SQL Server sql_variant 映射为 STRING");
            default -> mapGeneric(source, normalized);
        };
    }

    private MappingDecision mapGeneric(SourceTypeDescriptor source, NormalizedSourceType normalized) {
        String base = normalized.baseType();
        if (base == null || base.isBlank()) {
            return warn("STRING", "源端字段类型为空，兜底映射为 STRING");
        }
        return switch (base) {
            case "TINYINT" -> pass("TINYINT");
            case "SMALLINT" -> pass("SMALLINT");
            case "MEDIUMINT", "INT", "INTEGER" -> pass("INT");
            case "BIGINT" -> pass("BIGINT");
            case "LARGEINT" -> pass("LARGEINT");
            case "FLOAT", "REAL" -> pass("FLOAT");
            case "DOUBLE", "DOUBLE PRECISION" -> pass("DOUBLE");
            case "DECIMAL", "NUMERIC", "NUMBER" -> decimalOrDefault(source, "源端 DECIMAL/NUMERIC 缺少 precision/scale，使用 DECIMAL(38,10)");
            case "BOOLEAN", "BOOL" -> pass("BOOLEAN");
            case "DATE" -> pass("DATE");
            case "DATETIME", "TIMESTAMP" -> pass("DATETIME");
            case "CHAR", "NCHAR" -> pass(charType("CHAR", source.length()));
            case "VARCHAR", "VARCHAR2", "NVARCHAR", "NVARCHAR2" -> pass(varcharType(source.length()));
            case "TEXT", "CLOB", "NCLOB", "STRING", "JSON", "JSONB", "UUID", "BLOB", "BYTEA" ->
                    warn("STRING", "源端文本/复杂/大对象类型映射为 STRING");
            default -> warn("STRING", "未显式支持的源端类型 " + base + " 映射为 STRING");
        };
    }

    private MappingDecision oracleNumber(SourceTypeDescriptor source) {
        Integer precision = source.precision();
        Integer scale = source.scale();
        // ★ 修正：无精度且 scale=0 时映射为 BIGINT（与 decimalOrDefault 对齐）
        if (precision == null || precision <= 0) {
            if (scale == null || scale == 0) {
                return warn("BIGINT",
                    "Oracle NUMBER 无 precision 且 scale=0，视为整数映射为 BIGINT");
            }
            return warn("DECIMAL(38,10)",
                "Oracle NUMBER 无 precision/scale，使用 DECIMAL(38,10)");
        }
        int safeScale = scale == null ? 0 : Math.max(scale, 0);
        if (safeScale > 0) {
            return pass(decimalType(precision, safeScale));
        }
        if (precision <= 4) {
            return pass("SMALLINT");
        }
        if (precision <= 9) {
            return pass("INT");
        }
        if (precision <= 18) {
            return pass("BIGINT");
        }
        return pass(decimalType(precision, 0));
    }

    private MappingDecision decimalOrDefault(SourceTypeDescriptor source, String warning) {
        Integer precision = source.precision();
        Integer scale = source.scale();
        // 当 NUMERIC 没有 precision 且 scale=0 或 null 时，视为整数，映射为 BIGINT
        if (precision == null || precision <= 0) {
            if (scale == null || scale == 0) {
                return warn("BIGINT", "NUMERIC 无精度且 scale=0，视为整数映射为 BIGINT");
            }
            return warn("DECIMAL(38,10)", warning);
        }
        // 有 precision 但 scale=0 时，根据 precision 选择整数类型
        if (scale == null || scale == 0) {
            if (precision <= 4) return pass("SMALLINT");
            if (precision <= 9) return pass("INT");
            if (precision <= 18) return pass("BIGINT");
            // precision > 18 仍用 DECIMAL
        }
        return pass(decimalType(precision, scale == null ? 0 : Math.max(scale, 0)));
    }

    private String sqlServerString(SourceTypeDescriptor source, boolean unicode) {
        Integer length = source.length();
        if (length == null || length < 0 || length == Integer.MAX_VALUE || length >= 1_000_000) {
            return "STRING";
        }
        return varcharType(length);
    }

    private String varcharType(Integer sourceLength) {
        if (sourceLength == null || sourceLength <= 0) {
            return "STRING";
        }
        long bytes = Math.min(65_533L, Math.max(1L, sourceLength.longValue()) * 3L);
        return "VARCHAR(" + bytes + ")";
    }

    private String charType(String base, Integer sourceLength) {
        if (sourceLength == null || sourceLength <= 0) {
            return "STRING";
        }
        long bytes = Math.max(1L, sourceLength.longValue()) * 3L;
        if (bytes > 255L) {
            // CHAR 最大 255 字节，超出时降级为 VARCHAR
            return varcharType(sourceLength);
        }
        return base + "(" + bytes + ")";
    }

    private String decimalType(Integer precision, Integer scale) {
        int p = precision == null || precision <= 0 ? 38 : Math.min(38, precision);
        int s = scale == null || scale < 0 ? 0 : Math.min(p, scale);
        return "DECIMAL(" + p + "," + s + ")";
    }

    private NormalizedSourceType normalizeSource(SourceTypeDescriptor source) {
        String dialect = normalizeDialect(source == null ? null : source.sourceDialect());
        String raw = source == null ? "" : source.sourceTypeName();
        String upper = raw == null ? "" : raw.trim().toUpperCase(Locale.ROOT);
        boolean unsigned = upper.contains("UNSIGNED");
        upper = upper.replaceAll("\\s+UNSIGNED\\b", "").trim();
        String base = normalizeBase(upper);
        boolean timezone = base.contains("WITH TIME ZONE") || base.contains("LOCAL TIME ZONE")
                || "TIMESTAMPTZ".equals(base) || "DATETIMEOFFSET".equals(base);
        boolean binary = base.contains("BINARY") || base.contains("BLOB") || "BYTEA".equals(base)
                || "RAW".equals(base) || "ROWVERSION".equals(base);
        boolean lob = base.contains("CLOB") || base.contains("TEXT") || base.contains("BLOB")
                || "LONG".equals(base) || "NTEXT".equals(base) || "IMAGE".equals(base);
        boolean complex = base.contains("JSON") || "XMLTYPE".equals(base) || "SQL_VARIANT".equals(base)
                || "ARRAY".equals(base) || base.endsWith("[]");
        boolean unbounded = raw != null && raw.toUpperCase(Locale.ROOT).contains("(MAX)");
        return new NormalizedSourceType(dialect, base, unsigned, timezone, binary, lob, complex, unbounded);
    }

    private String normalizeDialect(String dialect) {
        if (dialect == null || dialect.isBlank()) {
            return "";
        }
        return dialect.trim().toUpperCase(Locale.ROOT)
                .replace(" ", "")
                .replace("-", "")
                .replace("_", "");
    }

    private static String normalizeBase(String type) {
        if (type == null) {
            return "";
        }
        String upper = type.trim().toUpperCase(Locale.ROOT);
        upper = upper.replaceAll("\\s+", " ");
        upper = upper.replace("(MAX)", "");
        Matcher matcher = Pattern.compile("^([^()]+)").matcher(upper);
        if (matcher.find()) {
            upper = matcher.group(1).trim();
        }
        return upper;
    }

    private boolean isCompatible(TypeSpec expected, TypeSpec actual) {
        String e = expected.base();
        String a = actual.base();
        if (e.equals(a)) {
            if (isTextBase(e)) {
                return hasEnoughTextCapacity(expected, actual);
            }
            if (isDecimalBase(e)) {
                return hasEnoughDecimalCapacity(expected, actual);
            }
            return true;
        }
        int er = integerRank(e);
        int ar = integerRank(a);
        if (er > 0 && ar > 0) {
            return ar >= er;
        }
        if (isTextBase(e) && isTextBase(a)) {
            return hasEnoughTextCapacity(expected, actual);
        }
        if (isDecimalBase(e) && isDecimalBase(a)) {
            return hasEnoughDecimalCapacity(expected, actual);
        }
        return isDateTimeCompatible(e, a);
    }

    private TypeSpec parseTypeSpec(String type, Integer charLength, Integer numericPrecision,
                                   Integer numericScale, Integer datetimePrecision) {
        String base = normalizeBase(type);
        Integer first = null;
        Integer second = null;
        if (type != null) {
            Matcher matcher = Pattern.compile("\\((\\d+)(?:\\s*,\\s*(\\d+))?\\)").matcher(type);
            if (matcher.find()) {
                first = Integer.parseInt(matcher.group(1));
                if (matcher.group(2) != null) {
                    second = Integer.parseInt(matcher.group(2));
                }
            }
        }
        return new TypeSpec(
                base,
                charLength != null ? charLength : first,
                numericPrecision != null ? numericPrecision : first,
                numericScale != null ? numericScale : second,
                datetimePrecision != null ? datetimePrecision : first);
    }

    private int integerRank(String type) {
        return switch (type) {
            case "BOOLEAN", "BOOL" -> 1;
            case "TINYINT" -> 2;
            case "SMALLINT" -> 3;
            case "INT", "INTEGER" -> 4;
            case "BIGINT" -> 5;
            case "LARGEINT" -> 6;
            default -> 0;
        };
    }

    private static boolean isTextBase(String base) {
        return "CHAR".equals(base) || "VARCHAR".equals(base) || "STRING".equals(base)
                || "TEXT".equals(base) || "JSON".equals(base);
    }

    private static boolean isDecimalBase(String base) {
        return "DECIMAL".equals(base) || "NUMERIC".equals(base);
    }

    private boolean isDateTimeCompatible(String expected, String actual) {
        if ("DATE".equals(expected)) {
            return "DATE".equals(actual) || "DATETIME".equals(actual);
        }
        if ("DATETIME".equals(expected)) {
            return "DATETIME".equals(actual);
        }
        return false;
    }

    private boolean hasEnoughTextCapacity(TypeSpec expected, TypeSpec actual) {
        if ("STRING".equals(actual.base()) || "TEXT".equals(actual.base()) || "JSON".equals(actual.base())) {
            return true;
        }
        if ("STRING".equals(expected.base()) || "TEXT".equals(expected.base()) || "JSON".equals(expected.base())) {
            return actual.length() != null && actual.length() >= DORIS_STRING_METADATA_MIN_LENGTH;
        }
        if (expected.length() == null || actual.length() == null) {
            return true;
        }
        return actual.length() >= expected.length();
    }

    private boolean hasEnoughDecimalCapacity(TypeSpec expected, TypeSpec actual) {
        if (expected.precision() == null || actual.precision() == null) {
            return true;
        }
        int expectedScale = expected.scale() == null ? 0 : expected.scale();
        int actualScale = actual.scale() == null ? 0 : actual.scale();
        int expectedIntegerDigits = expected.precision() - expectedScale;
        int actualIntegerDigits = actual.precision() - actualScale;
        return actualScale >= expectedScale && actualIntegerDigits >= expectedIntegerDigits;
    }

    private MappingDecision pass(String type) {
        return new MappingDecision(type, CompatibilityLevel.PASS, "");
    }

    private MappingDecision warn(String type, String reason) {
        return new MappingDecision(type, CompatibilityLevel.WARN, reason);
    }

    private record MappingDecision(String dorisType, CompatibilityLevel level, String reason) {
    }

    private record TypeSpec(String base, Integer length, Integer precision, Integer scale, Integer datetimePrecision) {
    }
}
