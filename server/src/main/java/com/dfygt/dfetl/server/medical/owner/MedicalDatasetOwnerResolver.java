package com.dfygt.dfetl.server.medical.owner;

import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * 医共体数据集负责人解析器。
 */
@Component
public class MedicalDatasetOwnerResolver {

    public static final String UNKNOWN_OWNER = "未提供负责人映射";

    private final Map<String, String> ownerByDatasetCode;

    public MedicalDatasetOwnerResolver() {
        this(Map.of());
    }

    private MedicalDatasetOwnerResolver(Map<String, String> ownerByDatasetCode) {
        this.ownerByDatasetCode = normalize(ownerByDatasetCode);
    }

    public static MedicalDatasetOwnerResolver fromMap(Map<String, String> ownerByDatasetCode) {
        return new MedicalDatasetOwnerResolver(ownerByDatasetCode);
    }

    public String resolveOwnerName(String datasetCode) {
        String normalized = normalize(datasetCode);
        if (normalized == null) {
            return UNKNOWN_OWNER;
        }
        String owner = ownerByDatasetCode.get(normalized);
        return owner == null || owner.isBlank() ? UNKNOWN_OWNER : owner;
    }

    private static Map<String, String> normalize(Map<String, String> values) {
        if (values == null || values.isEmpty()) {
            return Map.of();
        }
        Map<String, String> normalized = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : values.entrySet()) {
            String key = normalize(entry.getKey());
            String value = entry.getValue();
            if (key != null && value != null && !value.isBlank()) {
                normalized.put(key, value.trim());
            }
        }
        return Map.copyOf(normalized);
    }

    private static String normalize(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim().toUpperCase(Locale.ROOT);
    }
}
