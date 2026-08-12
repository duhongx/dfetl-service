package com.dfygt.dfetl.server.medical;

import com.dfygt.dfetl.server.entity.SyncTask;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/** 医共体任务契约快照的固化、语义 hash 和运行时漂移校验。 */
public final class MedicalContractSnapshotCodec {

    public static final String SNAPSHOT_KEY = "medicalContractSnapshot";
    public static final String HASH_KEY = "medicalContractHash";

    private static final ObjectMapper CANONICAL_MAPPER = new ObjectMapper();

    private MedicalContractSnapshotCodec() {
    }

    public static void pin(
            Map<String, Object> characteristics,
            MedicalDatasetContract contract,
            ObjectMapper objectMapper) {
        if (characteristics == null || contract == null) {
            throw new IllegalArgumentException("医共体契约快照参数不能为空");
        }
        ObjectMapper mapper = objectMapper == null ? CANONICAL_MAPPER : objectMapper;
        characteristics.put(SNAPSHOT_KEY, mapper.convertValue(contract, Map.class));
        characteristics.put(HASH_KEY,
                semanticHash(contract, parseFieldMapping(characteristics.get("fieldMapping"))));
    }

    public static MedicalDatasetContract resolveForTask(
            SyncTask task,
            MedicalDatasetContractService contractService,
            ObjectMapper objectMapper) {
        if (task == null || task.getDataCharacteristics() == null
                || task.getDataCharacteristics().isBlank()) {
            throw new IllegalStateException("BLOCKED_CONTRACT_SNAPSHOT_MISSING: 任务缺少医共体契约快照");
        }
        if (contractService == null) {
            throw new IllegalStateException("医共体契约服务未启用");
        }
        ObjectMapper mapper = objectMapper == null ? CANONICAL_MAPPER : objectMapper;
        try {
            Map<String, Object> values = mapper.readValue(
                    task.getDataCharacteristics(), new TypeReference<LinkedHashMap<String, Object>>() { });
            Object snapshotValue = values.get(SNAPSHOT_KEY);
            String savedHash = normalized(values.get(HASH_KEY));
            if (snapshotValue == null || savedHash == null) {
                throw new IllegalStateException(
                        "BLOCKED_CONTRACT_SNAPSHOT_MISSING: 医共体历史任务需要删除并重建");
            }
            MedicalDatasetContract snapshot = mapper.convertValue(
                    snapshotValue, MedicalDatasetContract.class);
            Map<String, String> fieldMapping = parseFieldMapping(values.get("fieldMapping"));
            String snapshotHash = semanticHash(snapshot, fieldMapping);
            if (!savedHash.equalsIgnoreCase(snapshotHash)) {
                throw new IllegalStateException(
                        "BLOCKED_CONTRACT_SNAPSHOT_INVALID: 契约快照或 fieldMapping 已被修改");
            }
            String datasetCode = normalized(values.get("matchedDatasetCode"));
            if (datasetCode == null) {
                throw new IllegalStateException(
                        "BLOCKED_CONTRACT_SNAPSHOT_INVALID: matchedDatasetCode 缺失");
            }
            MedicalDatasetContract live = contractService.loadByDatasetCode(datasetCode);
            String snapshotDefinitionHash = definitionHash(snapshot, fieldMapping);
            String liveDefinitionHash = definitionHash(live, fieldMapping);
            if (!snapshotDefinitionHash.equalsIgnoreCase(liveDefinitionHash)) {
                throw new IllegalStateException(
                        "BLOCKED_CONTRACT_DRIFT: 医共体数据项定义已变化，请删除并重建任务和 Doris 表"
                                + "，dataset=" + datasetCode);
            }
            // dorisType 是 dfetl 派生的物理采集策略，不属于医共体业务定义。
            // 策略升级后返回实时派生契约，避免历史快照把 Reader/DDL 锁死在旧容量。
            return live;
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException(
                    "BLOCKED_CONTRACT_SNAPSHOT_INVALID: 无法解析医共体契约快照: " + e.getMessage(), e);
        }
    }

    public static String semanticHash(
            MedicalDatasetContract contract, Map<String, String> fieldMapping) {
        return contractHash(contract, fieldMapping, true, false);
    }

    private static String definitionHash(
            MedicalDatasetContract contract, Map<String, String> fieldMapping) {
        return contractHash(contract, fieldMapping, false, true);
    }

    private static String contractHash(
            MedicalDatasetContract contract,
            Map<String, String> fieldMapping,
            boolean includePhysicalStorageType,
            boolean normalizePrimaryKeyOrder) {
        if (contract == null) {
            throw new IllegalArgumentException("医共体契约不能为空");
        }
        Map<String, Object> canonical = new LinkedHashMap<>();
        canonical.put("datasetCode", normalizedUpper(contract.datasetCode()));
        canonical.put("datasetName", normalized(contract.datasetName()));
        canonical.put("version", normalized(contract.version()));
        canonical.put("targetTable", normalizedLower(contract.targetTable()));
        List<String> primaryKeys = new ArrayList<>(normalizeOrdered(contract.primaryKeys(), true));
        if (normalizePrimaryKeyOrder) {
            primaryKeys.sort(Comparator.nullsFirst(String::compareTo));
        }
        canonical.put("primaryKeys", primaryKeys);
        canonical.put("incrementalField", normalizedUpper(contract.incrementalField()));
        canonical.put("deleteField", normalizedUpper(contract.deleteField()));

        List<Map<String, Object>> fields = new ArrayList<>();
        if (contract.fields() != null) {
            for (MedicalFieldContract field : contract.fields()) {
                Map<String, Object> canonicalField = new LinkedHashMap<>();
                canonicalField.put("code", normalizedUpper(field.code()));
                canonicalField.put("name", normalized(field.name()));
                canonicalField.put("sdvType", normalizedUpper(field.sdvType()));
                canonicalField.put("format", normalizedUpper(field.format()));
                canonicalField.put("primaryKey", field.primaryKey());
                canonicalField.put("notNull", field.notNull());
                canonicalField.put("valueDomainCode", normalizedUpper(field.valueDomainCode()));
                canonicalField.put("dorisColumn", normalizedLower(field.dorisColumn()));
                if (includePhysicalStorageType) {
                    canonicalField.put("dorisType", normalizedUpper(field.dorisType()));
                }
                fields.add(canonicalField);
            }
        }
        fields.sort(Comparator
                .comparing((Map<String, Object> field) -> String.valueOf(field.get("dorisColumn")))
                .thenComparing(field -> String.valueOf(field.get("code"))));
        canonical.put("fields", fields);

        Map<String, String> normalizedMapping = new TreeMap<>();
        if (fieldMapping != null) {
            fieldMapping.forEach((key, value) -> {
                String normalizedKey = normalized(key);
                String normalizedValue = normalized(value);
                if (normalizedKey != null && normalizedValue != null) {
                    normalizedMapping.put(normalizedKey, normalizedValue);
                }
            });
        }
        canonical.put("fieldMapping", normalizedMapping);
        try {
            byte[] json = CANONICAL_MAPPER.writeValueAsString(canonical)
                    .getBytes(StandardCharsets.UTF_8);
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(json));
        } catch (Exception e) {
            throw new IllegalStateException("医共体契约 hash 生成失败: " + e.getMessage(), e);
        }
    }

    private static Map<String, String> parseFieldMapping(Object value) {
        if (value == null) {
            return Map.of();
        }
        if (!(value instanceof Map<?, ?> raw)) {
            throw new IllegalStateException("fieldMapping 不是对象");
        }
        Map<String, String> result = new LinkedHashMap<>();
        raw.forEach((key, mapped) -> {
            String source = normalized(key);
            String contract = normalized(mapped);
            if (source == null || contract == null) {
                throw new IllegalStateException("fieldMapping 包含空字段");
            }
            result.put(source, contract);
        });
        return Map.copyOf(result);
    }

    private static List<String> normalizeOrdered(List<String> values, boolean uppercase) {
        if (values == null) {
            return List.of();
        }
        return values.stream()
                .map(value -> uppercase ? normalizedUpper(value) : normalized(value))
                .toList();
    }

    private static String normalized(Object value) {
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value).trim();
        return text.isEmpty() ? null : text;
    }

    private static String normalizedUpper(Object value) {
        String text = normalized(value);
        return text == null ? null : text.toUpperCase(Locale.ROOT);
    }

    private static String normalizedLower(Object value) {
        String text = normalized(value);
        return text == null ? null : text.toLowerCase(Locale.ROOT);
    }
}
