package com.dfygt.dfetl.server.medical.source;

import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 源库方言适配器解析器。
 *
 * <p>国产数据库优先通过兼容模式映射到现有主流方言，避免 SQL 生成链路散落私有库判断。</p>
 */
@Component
public class SourceDialectAdapterResolver {

    private static final Map<String, String> ALIASES = aliases();

    private final Map<String, SourceDialectAdapter> adaptersByDialect;

    public SourceDialectAdapterResolver(List<SourceDialectAdapter> adapters) {
        this.adaptersByDialect = new HashMap<>();
        if (adapters != null) {
            for (SourceDialectAdapter adapter : adapters) {
                if (adapter == null || adapter.dialect() == null || adapter.dialect().isBlank()) {
                    continue;
                }
                adaptersByDialect.put(normalize(adapter.dialect()), adapter);
            }
        }
    }

    public SourceDialectAdapter resolve(String datasourceType) {
        return resolve(datasourceType, null);
    }

    public SourceDialectAdapter resolve(String datasourceType, String compatibilityMode) {
        String requested = firstNonBlank(compatibilityMode, datasourceType);
        String canonical = canonicalDialect(requested);
        SourceDialectAdapter adapter = adaptersByDialect.get(canonical);
        if (adapter == null) {
            throw new IllegalArgumentException("不支持的源库方言: " + requested
                    + "，请配置兼容模式为 POSTGRESQL / ORACLE / MYSQL / SQLSERVER");
        }
        return adapter;
    }

    public String canonicalDialect(String datasourceType) {
        String normalized = normalize(datasourceType);
        if (normalized.isBlank()) {
            throw new IllegalArgumentException("源库方言不能为空");
        }
        return ALIASES.getOrDefault(normalized, normalized);
    }

    private static String firstNonBlank(String first, String fallback) {
        if (first != null && !first.isBlank()) {
            return first;
        }
        return fallback;
    }

    private static String normalize(String value) {
        if (value == null) {
            return "";
        }
        return value.trim()
                .replace("-", "")
                .replace("_", "")
                .replace(" ", "")
                .toUpperCase(Locale.ROOT);
    }

    private static Map<String, String> aliases() {
        Map<String, String> aliases = new HashMap<>();
        aliases.put("POSTGRESQL", "POSTGRESQL");
        aliases.put("POSTGRES", "POSTGRESQL");
        aliases.put("PG", "POSTGRESQL");
        aliases.put("OPENGAUSS", "POSTGRESQL");
        aliases.put("GAUSSDB", "POSTGRESQL");
        aliases.put("KINGBASE", "POSTGRESQL");
        aliases.put("KINGBASEES", "POSTGRESQL");
        aliases.put("VASTBASE", "POSTGRESQL");

        aliases.put("ORACLE", "ORACLE");
        aliases.put("DM", "ORACLE");
        aliases.put("DAMENG", "ORACLE");

        aliases.put("MYSQL", "MYSQL");
        aliases.put("MARIADB", "MYSQL");
        aliases.put("OCEANBASE", "MYSQL");
        aliases.put("OCEANBASEMYSQL", "MYSQL");

        aliases.put("SQLSERVER", "SQLSERVER");
        aliases.put("MSSQL", "SQLSERVER");
        aliases.put("MICROSOFTSQLSERVER", "SQLSERVER");
        return Map.copyOf(aliases);
    }
}
