package com.dfygt.dfetl.server.engine.doris;

import com.dfygt.dfetl.server.dto.DorisTypeMappingRuleDto;
import com.dfygt.dfetl.server.engine.doris.DorisTypeMappingPolicy.CompatibilityLevel;
import com.dfygt.dfetl.server.engine.doris.DorisTypeMappingPolicy.MappingResult;
import com.dfygt.dfetl.server.engine.doris.DorisTypeMappingPolicy.NormalizedSourceType;
import com.dfygt.dfetl.server.engine.doris.DorisTypeMappingPolicy.SourceTypeDescriptor;
import com.dfygt.dfetl.server.entity.DorisTypeMappingRule;
import com.dfygt.dfetl.server.repository.DorisTypeMappingRuleRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.regex.Pattern;

@Service
@RequiredArgsConstructor
public class DorisTypeMappingRuleService {

    private final DorisTypeMappingRuleRepository repository;
    private final DorisTypeMappingPolicy fallbackPolicy;

    public MappingResult recommend(SourceTypeDescriptor source) {
        MappingResult fallback = fallbackPolicy.recommend(source);
        NormalizedSourceType normalized = fallback.normalized();
        for (DorisTypeMappingRule rule : repository.findByEnabledTrueOrderByPriorityDescIdAsc()) {
            if (!dialectMatches(rule, normalized)) continue;
            if (!typeMatches(rule, normalized.baseType())) continue;
            CompatibilityLevel level = parseLevel(rule.getCompatibilityLevel());
            String ruleType = rule.getRecommendedDorisType();
            // 如果规则是通用最大长度（如 VARCHAR(65533)），但 fallback 有精确长度，使用 fallback
            String fallbackType = fallback.recommendedDorisType();
            if (fallbackType != null && isMorePrecise(fallbackType, ruleType)) {
                return new MappingResult(
                        fallbackType,
                        level,
                        rule.getReason() == null ? "" : rule.getReason(),
                        normalized);
            }
            return new MappingResult(
                    ruleType,
                    level,
                    rule.getReason() == null ? "" : rule.getReason(),
                    normalized);
        }
        return fallback;
    }

    /**
     * 判断 fallback 类型是否比规则类型更精确（有具体长度/精度 vs 最大长度/精度）。
     * 例如：VARCHAR(300) 比 VARCHAR(65533) 更精确；DECIMAL(10,2) 比 DECIMAL(38,18) 更精确。
     */
    private boolean isMorePrecise(String fallbackType, String ruleType) {
        if (fallbackType == null || ruleType == null) return false;
        String fb = fallbackType.toUpperCase(Locale.ROOT).trim();
        String rt = ruleType.toUpperCase(Locale.ROOT).trim();
        // 只对 VARCHAR/CHAR 类型做精确度比较
        if (fb.startsWith("VARCHAR(") && rt.startsWith("VARCHAR(")) {
            int fbLen = extractLength(fb);
            int rtLen = extractLength(rt);
            // fallback 有精确长度（<65533）且规则是最大长度（65533），使用 fallback
            return fbLen > 0 && fbLen < 65533 && rtLen >= 65533;
        }
        if (fb.startsWith("CHAR(") && rt.startsWith("CHAR(")) {
            int fbLen = extractLength(fb);
            int rtLen = extractLength(rt);
            return fbLen > 0 && fbLen < rtLen;
        }
        // DECIMAL/NUMERIC：fallback 有源端精确 precision/scale 时优先于默认规则的最大精度
        if (fb.startsWith("DECIMAL(") && rt.startsWith("DECIMAL(")) {
            int[] fbPs = extractPrecisionScale(fb);
            int[] rtPs = extractPrecisionScale(rt);
            if (fbPs != null && rtPs != null) {
                // fallback 有精确精度（<38）且规则是最大精度（38），使用 fallback
                return fbPs[0] > 0 && fbPs[0] < 38 && rtPs[0] >= 38;
            }
        }
        // 整数类型：fallback 推断出具体整数类型（如 BIGINT/INT）时优先于规则的 DECIMAL(38,18)
        if (!fb.startsWith("DECIMAL(") && rt.startsWith("DECIMAL(")) {
            // fallback 是整数类型（SMALLINT/INT/BIGINT），规则是 DECIMAL，使用 fallback
            String fbBase = fb.contains("(") ? fb.substring(0, fb.indexOf('(')) : fb;
            return "SMALLINT".equals(fbBase) || "INT".equals(fbBase) || "BIGINT".equals(fbBase)
                    || "TINYINT".equals(fbBase) || "LARGEINT".equals(fbBase);
        }
        return false;
    }

    private int[] extractPrecisionScale(String type) {
        int start = type.indexOf('(');
        int end = type.indexOf(')');
        if (start < 0 || end < 0 || end <= start + 1) return null;
        String inner = type.substring(start + 1, end).trim();
        String[] parts = inner.split(",");
        if (parts.length != 2) return null;
        try {
            return new int[]{Integer.parseInt(parts[0].trim()), Integer.parseInt(parts[1].trim())};
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private int extractLength(String type) {
        int start = type.indexOf('(');
        int end = type.indexOf(')');
        if (start < 0 || end < 0 || end <= start + 1) return -1;
        try {
            return Integer.parseInt(type.substring(start + 1, end).trim());
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    public List<DorisTypeMappingRuleDto> list() {
        return repository.findAllByOrderBySourceDialectAscPriorityDescIdAsc().stream()
                .map(this::toDto)
                .toList();
    }

    @Transactional
    public DorisTypeMappingRuleDto update(Long id, DorisTypeMappingRuleDto dto) {
        DorisTypeMappingRule rule = repository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Doris 类型映射规则不存在: " + id));
        if (dto.getRecommendedDorisType() != null && !dto.getRecommendedDorisType().isBlank()) {
            rule.setRecommendedDorisType(dto.getRecommendedDorisType().trim().toUpperCase(Locale.ROOT));
        }
        if (dto.getCompatibilityLevel() != null && !dto.getCompatibilityLevel().isBlank()) {
            rule.setCompatibilityLevel(parseLevel(dto.getCompatibilityLevel()).name());
        }
        rule.setReason(dto.getReason());
        if (dto.getEnabled() != null) rule.setEnabled(dto.getEnabled());
        if (dto.getPriority() != null) rule.setPriority(dto.getPriority());
        return toDto(repository.save(rule));
    }

    @Transactional
    public DorisTypeMappingRuleDto create(DorisTypeMappingRuleDto dto) {
        DorisTypeMappingRule rule = new DorisTypeMappingRule();
        rule.setProfileName(dto.getProfileName() != null ? dto.getProfileName() : "DEFAULT");
        rule.setProfileVersion(dto.getProfileVersion() != null ? dto.getProfileVersion() : 1);
        rule.setSourceDialect(dto.getSourceDialect());
        rule.setSourceTypePattern(dto.getSourceTypePattern());
        rule.setRecommendedDorisType(dto.getRecommendedDorisType());
        rule.setCompatibilityLevel(dto.getCompatibilityLevel() != null ? dto.getCompatibilityLevel() : "PASS");
        rule.setReason(dto.getReason());
        rule.setEnabled(dto.getEnabled() != null ? dto.getEnabled() : true);
        rule.setPriority(dto.getPriority() != null ? dto.getPriority() : 100);
        return toDto(repository.save(rule));
    }

    @Transactional
    public Map<String, Object> initDefaults() {
        List<DefaultRule> defaults = buildDefaultRules();
        int inserted = 0;
        int skipped = 0;
        for (DefaultRule dr : defaults) {
            if (repository.existsBySourceDialectAndSourceTypePattern(dr.dialect, dr.pattern)) {
                skipped++;
                continue;
            }
            DorisTypeMappingRule rule = new DorisTypeMappingRule();
            rule.setProfileName("DEFAULT");
            rule.setProfileVersion(1);
            rule.setSourceDialect(dr.dialect);
            rule.setSourceTypePattern(dr.pattern);
            rule.setRecommendedDorisType(dr.dorisType);
            rule.setCompatibilityLevel("PASS");
            rule.setReason(dr.reason);
            rule.setEnabled(true);
            rule.setPriority(100);
            repository.save(rule);
            inserted++;
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("inserted", inserted);
        result.put("skipped", skipped);
        result.put("total", defaults.size());
        return result;
    }

    private record DefaultRule(String dialect, String pattern, String dorisType, String reason) {}

    private List<DefaultRule> buildDefaultRules() {
        List<DefaultRule> rules = new ArrayList<>();

        // PostgreSQL
        rules.add(new DefaultRule("POSTGRESQL", "VARCHAR", "VARCHAR(65533)", "PostgreSQL VARCHAR → Doris VARCHAR"));
        rules.add(new DefaultRule("POSTGRESQL", "TEXT", "STRING", "PostgreSQL TEXT → Doris STRING"));
        rules.add(new DefaultRule("POSTGRESQL", "CHAR", "CHAR(255)", "PostgreSQL CHAR → Doris CHAR"));
        rules.add(new DefaultRule("POSTGRESQL", "INT2", "SMALLINT", "PostgreSQL INT2 → Doris SMALLINT"));
        rules.add(new DefaultRule("POSTGRESQL", "SMALLINT", "SMALLINT", "PostgreSQL SMALLINT → Doris SMALLINT"));
        rules.add(new DefaultRule("POSTGRESQL", "INT4", "INT", "PostgreSQL INT4 → Doris INT"));
        rules.add(new DefaultRule("POSTGRESQL", "INTEGER", "INT", "PostgreSQL INTEGER → Doris INT"));
        rules.add(new DefaultRule("POSTGRESQL", "INT8", "BIGINT", "PostgreSQL INT8 → Doris BIGINT"));
        rules.add(new DefaultRule("POSTGRESQL", "BIGINT", "BIGINT", "PostgreSQL BIGINT → Doris BIGINT"));
        rules.add(new DefaultRule("POSTGRESQL", "FLOAT4", "FLOAT", "PostgreSQL FLOAT4 → Doris FLOAT"));
        rules.add(new DefaultRule("POSTGRESQL", "REAL", "FLOAT", "PostgreSQL REAL → Doris FLOAT"));
        rules.add(new DefaultRule("POSTGRESQL", "FLOAT8", "DOUBLE", "PostgreSQL FLOAT8 → Doris DOUBLE"));
        rules.add(new DefaultRule("POSTGRESQL", "DOUBLE PRECISION", "DOUBLE", "PostgreSQL DOUBLE PRECISION → Doris DOUBLE"));
        rules.add(new DefaultRule("POSTGRESQL", "NUMERIC", "DECIMAL(38,18)", "PostgreSQL NUMERIC → Doris DECIMAL"));
        rules.add(new DefaultRule("POSTGRESQL", "DECIMAL", "DECIMAL(38,18)", "PostgreSQL DECIMAL → Doris DECIMAL"));
        rules.add(new DefaultRule("POSTGRESQL", "BOOL", "BOOLEAN", "PostgreSQL BOOL → Doris BOOLEAN"));
        rules.add(new DefaultRule("POSTGRESQL", "BOOLEAN", "BOOLEAN", "PostgreSQL BOOLEAN → Doris BOOLEAN"));
        rules.add(new DefaultRule("POSTGRESQL", "DATE", "DATE", "PostgreSQL DATE → Doris DATE"));
        rules.add(new DefaultRule("POSTGRESQL", "TIMESTAMP", "DATETIME(6)", "PostgreSQL TIMESTAMP → Doris DATETIME(6)"));
        rules.add(new DefaultRule("POSTGRESQL", "TIMESTAMPTZ", "DATETIME(6)", "PostgreSQL TIMESTAMPTZ → Doris DATETIME(6)"));
        rules.add(new DefaultRule("POSTGRESQL", "TIMESTAMP WITH TIME ZONE", "DATETIME(6)", "PostgreSQL TIMESTAMP WITH TIME ZONE → Doris DATETIME(6)"));
        rules.add(new DefaultRule("POSTGRESQL", "BYTEA", "STRING", "PostgreSQL BYTEA → Doris STRING"));
        rules.add(new DefaultRule("POSTGRESQL", "JSON", "STRING", "PostgreSQL JSON → Doris STRING"));
        rules.add(new DefaultRule("POSTGRESQL", "JSONB", "STRING", "PostgreSQL JSONB → Doris STRING"));
        rules.add(new DefaultRule("POSTGRESQL", "UUID", "VARCHAR(36)", "PostgreSQL UUID → Doris VARCHAR(36)"));

        // MySQL
        rules.add(new DefaultRule("MYSQL", "VARCHAR", "VARCHAR(65533)", "MySQL VARCHAR → Doris VARCHAR"));
        rules.add(new DefaultRule("MYSQL", "TEXT", "STRING", "MySQL TEXT → Doris STRING"));
        rules.add(new DefaultRule("MYSQL", "MEDIUMTEXT", "STRING", "MySQL MEDIUMTEXT → Doris STRING"));
        rules.add(new DefaultRule("MYSQL", "LONGTEXT", "STRING", "MySQL LONGTEXT → Doris STRING"));
        rules.add(new DefaultRule("MYSQL", "TINYTEXT", "STRING", "MySQL TINYTEXT → Doris STRING"));
        rules.add(new DefaultRule("MYSQL", "CHAR", "CHAR(255)", "MySQL CHAR → Doris CHAR"));
        rules.add(new DefaultRule("MYSQL", "TINYINT", "TINYINT", "MySQL TINYINT → Doris TINYINT"));
        rules.add(new DefaultRule("MYSQL", "SMALLINT", "SMALLINT", "MySQL SMALLINT → Doris SMALLINT"));
        rules.add(new DefaultRule("MYSQL", "INT", "INT", "MySQL INT → Doris INT"));
        rules.add(new DefaultRule("MYSQL", "INTEGER", "INT", "MySQL INTEGER → Doris INT"));
        rules.add(new DefaultRule("MYSQL", "BIGINT", "BIGINT", "MySQL BIGINT → Doris BIGINT"));
        rules.add(new DefaultRule("MYSQL", "FLOAT", "FLOAT", "MySQL FLOAT → Doris FLOAT"));
        rules.add(new DefaultRule("MYSQL", "DOUBLE", "DOUBLE", "MySQL DOUBLE → Doris DOUBLE"));
        rules.add(new DefaultRule("MYSQL", "DECIMAL", "DECIMAL(38,18)", "MySQL DECIMAL → Doris DECIMAL"));
        rules.add(new DefaultRule("MYSQL", "DATE", "DATE", "MySQL DATE → Doris DATE"));
        rules.add(new DefaultRule("MYSQL", "DATETIME", "DATETIME(6)", "MySQL DATETIME → Doris DATETIME(6)"));
        rules.add(new DefaultRule("MYSQL", "TIMESTAMP", "DATETIME(6)", "MySQL TIMESTAMP → Doris DATETIME(6)"));
        rules.add(new DefaultRule("MYSQL", "JSON", "STRING", "MySQL JSON → Doris STRING"));
        rules.add(new DefaultRule("MYSQL", "BLOB", "STRING", "MySQL BLOB → Doris STRING"));
        rules.add(new DefaultRule("MYSQL", "MEDIUMBLOB", "STRING", "MySQL MEDIUMBLOB → Doris STRING"));
        rules.add(new DefaultRule("MYSQL", "LONGBLOB", "STRING", "MySQL LONGBLOB → Doris STRING"));
        rules.add(new DefaultRule("MYSQL", "BIT", "BOOLEAN", "MySQL BIT → Doris BOOLEAN"));

        // Oracle
        rules.add(new DefaultRule("ORACLE", "VARCHAR2", "VARCHAR(65533)", "Oracle VARCHAR2 → Doris VARCHAR"));
        rules.add(new DefaultRule("ORACLE", "NVARCHAR2", "VARCHAR(65533)", "Oracle NVARCHAR2 → Doris VARCHAR"));
        rules.add(new DefaultRule("ORACLE", "CHAR", "CHAR(255)", "Oracle CHAR → Doris CHAR"));
        rules.add(new DefaultRule("ORACLE", "NCHAR", "CHAR(255)", "Oracle NCHAR → Doris CHAR"));
        rules.add(new DefaultRule("ORACLE", "CLOB", "STRING", "Oracle CLOB → Doris STRING"));
        rules.add(new DefaultRule("ORACLE", "NCLOB", "STRING", "Oracle NCLOB → Doris STRING"));
        rules.add(new DefaultRule("ORACLE", "NUMBER", "DECIMAL(38,18)", "Oracle NUMBER → Doris DECIMAL"));
        rules.add(new DefaultRule("ORACLE", "FLOAT", "DOUBLE", "Oracle FLOAT → Doris DOUBLE"));
        rules.add(new DefaultRule("ORACLE", "BINARY_FLOAT", "FLOAT", "Oracle BINARY_FLOAT → Doris FLOAT"));
        rules.add(new DefaultRule("ORACLE", "BINARY_DOUBLE", "DOUBLE", "Oracle BINARY_DOUBLE → Doris DOUBLE"));
        rules.add(new DefaultRule("ORACLE", "DATE", "DATETIME", "Oracle DATE → Doris DATETIME"));
        rules.add(new DefaultRule("ORACLE", "TIMESTAMP", "DATETIME(6)", "Oracle TIMESTAMP → Doris DATETIME(6)"));
        rules.add(new DefaultRule("ORACLE", "TIMESTAMP WITH TIME ZONE", "DATETIME(6)", "Oracle TIMESTAMP WITH TIME ZONE → Doris DATETIME(6)"));
        rules.add(new DefaultRule("ORACLE", "TIMESTAMP WITH LOCAL TIME ZONE", "DATETIME(6)", "Oracle TIMESTAMP WITH LOCAL TIME ZONE → Doris DATETIME(6)"));
        rules.add(new DefaultRule("ORACLE", "RAW", "STRING", "Oracle RAW → Doris STRING"));
        rules.add(new DefaultRule("ORACLE", "BLOB", "STRING", "Oracle BLOB → Doris STRING"));
        rules.add(new DefaultRule("ORACLE", "LONG", "STRING", "Oracle LONG → Doris STRING"));

        // SQL Server
        rules.add(new DefaultRule("SQLSERVER", "VARCHAR", "VARCHAR(65533)", "SQL Server VARCHAR → Doris VARCHAR"));
        rules.add(new DefaultRule("SQLSERVER", "NVARCHAR", "VARCHAR(65533)", "SQL Server NVARCHAR → Doris VARCHAR"));
        rules.add(new DefaultRule("SQLSERVER", "TEXT", "STRING", "SQL Server TEXT → Doris STRING"));
        rules.add(new DefaultRule("SQLSERVER", "NTEXT", "STRING", "SQL Server NTEXT → Doris STRING"));
        rules.add(new DefaultRule("SQLSERVER", "CHAR", "CHAR(255)", "SQL Server CHAR → Doris CHAR"));
        rules.add(new DefaultRule("SQLSERVER", "NCHAR", "CHAR(255)", "SQL Server NCHAR → Doris CHAR"));
        rules.add(new DefaultRule("SQLSERVER", "TINYINT", "TINYINT", "SQL Server TINYINT → Doris TINYINT"));
        rules.add(new DefaultRule("SQLSERVER", "SMALLINT", "SMALLINT", "SQL Server SMALLINT → Doris SMALLINT"));
        rules.add(new DefaultRule("SQLSERVER", "INT", "INT", "SQL Server INT → Doris INT"));
        rules.add(new DefaultRule("SQLSERVER", "BIGINT", "BIGINT", "SQL Server BIGINT → Doris BIGINT"));
        rules.add(new DefaultRule("SQLSERVER", "FLOAT", "DOUBLE", "SQL Server FLOAT → Doris DOUBLE"));
        rules.add(new DefaultRule("SQLSERVER", "REAL", "FLOAT", "SQL Server REAL → Doris FLOAT"));
        rules.add(new DefaultRule("SQLSERVER", "DECIMAL", "DECIMAL(38,18)", "SQL Server DECIMAL → Doris DECIMAL"));
        rules.add(new DefaultRule("SQLSERVER", "NUMERIC", "DECIMAL(38,18)", "SQL Server NUMERIC → Doris DECIMAL"));
        rules.add(new DefaultRule("SQLSERVER", "MONEY", "DECIMAL(19,4)", "SQL Server MONEY → Doris DECIMAL(19,4)"));
        rules.add(new DefaultRule("SQLSERVER", "SMALLMONEY", "DECIMAL(10,4)", "SQL Server SMALLMONEY → Doris DECIMAL(10,4)"));
        rules.add(new DefaultRule("SQLSERVER", "DATE", "DATE", "SQL Server DATE → Doris DATE"));
        rules.add(new DefaultRule("SQLSERVER", "DATETIME", "DATETIME(6)", "SQL Server DATETIME → Doris DATETIME(6)"));
        rules.add(new DefaultRule("SQLSERVER", "DATETIME2", "DATETIME(6)", "SQL Server DATETIME2 → Doris DATETIME(6)"));
        rules.add(new DefaultRule("SQLSERVER", "DATETIMEOFFSET", "DATETIME(6)", "SQL Server DATETIMEOFFSET → Doris DATETIME(6)"));
        rules.add(new DefaultRule("SQLSERVER", "SMALLDATETIME", "DATETIME", "SQL Server SMALLDATETIME → Doris DATETIME"));
        rules.add(new DefaultRule("SQLSERVER", "BIT", "BOOLEAN", "SQL Server BIT → Doris BOOLEAN"));
        rules.add(new DefaultRule("SQLSERVER", "UNIQUEIDENTIFIER", "VARCHAR(36)", "SQL Server UNIQUEIDENTIFIER → Doris VARCHAR(36)"));
        rules.add(new DefaultRule("SQLSERVER", "VARBINARY", "STRING", "SQL Server VARBINARY → Doris STRING"));
        rules.add(new DefaultRule("SQLSERVER", "IMAGE", "STRING", "SQL Server IMAGE → Doris STRING"));
        rules.add(new DefaultRule("SQLSERVER", "XML", "STRING", "SQL Server XML → Doris STRING"));

        return rules;
    }

    private boolean dialectMatches(DorisTypeMappingRule rule, NormalizedSourceType normalized) {
        String expected = normalize(rule.getSourceDialect());
        return "*".equals(expected) || expected.equals(normalize(normalized.dialect()));
    }

    private boolean typeMatches(DorisTypeMappingRule rule, String baseType) {
        String pattern = rule.getSourceTypePattern() == null ? "" : rule.getSourceTypePattern().trim().toUpperCase(Locale.ROOT);
        String base = baseType == null ? "" : baseType.trim().toUpperCase(Locale.ROOT);
        if ("*".equals(pattern)) return true;
        if (pattern.startsWith("REGEX:")) {
            return Pattern.compile(pattern.substring("REGEX:".length()), Pattern.CASE_INSENSITIVE)
                    .matcher(base)
                    .matches();
        }
        return pattern.equals(base);
    }

    private CompatibilityLevel parseLevel(String level) {
        try {
            return CompatibilityLevel.valueOf(level == null ? "PASS" : level.trim().toUpperCase(Locale.ROOT));
        } catch (Exception e) {
            throw new IllegalArgumentException("非法 Doris 类型兼容等级: " + level);
        }
    }

    private String normalize(String value) {
        if (value == null) return "";
        return value.trim().toUpperCase(Locale.ROOT).replace("_", "").replace("-", "").replace(" ", "");
    }

    private DorisTypeMappingRuleDto toDto(DorisTypeMappingRule rule) {
        DorisTypeMappingRuleDto dto = new DorisTypeMappingRuleDto();
        dto.setId(rule.getId());
        dto.setProfileName(rule.getProfileName());
        dto.setProfileVersion(rule.getProfileVersion());
        dto.setSourceDialect(rule.getSourceDialect());
        dto.setSourceTypePattern(rule.getSourceTypePattern());
        dto.setRecommendedDorisType(rule.getRecommendedDorisType());
        dto.setCompatibilityLevel(rule.getCompatibilityLevel());
        dto.setReason(rule.getReason());
        dto.setEnabled(rule.getEnabled());
        dto.setPriority(rule.getPriority());
        return dto;
    }
}
