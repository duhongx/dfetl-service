package com.dfygt.dfetl.server.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Arrays;
import java.util.List;

/**
 * Encodes diff primary-key values for storage in etl_verify_diff.pk_value.
 *
 * <p>Single-column keys stay as the historical plain value. Composite keys use
 * a JSON array so column values may safely contain the old human separator '|'.
 */
final class PkValueCodec {

    static final char INTERNAL_SEPARATOR = '\u001e';

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() {};

    private PkValueCodec() {
    }

    static String encode(List<String> values) {
        if (values == null || values.isEmpty()) {
            throw new IllegalArgumentException("PK 值不能为空");
        }
        if (values.size() == 1) {
            return values.get(0);
        }
        try {
            return OBJECT_MAPPER.writeValueAsString(values);
        } catch (Exception e) {
            throw new IllegalArgumentException("PK 值编码失败: " + e.getMessage(), e);
        }
    }

    static String encodeInternalKey(String internalKey, int expectedCount) {
        if (expectedCount <= 1) {
            return internalKey;
        }
        String[] parts = internalKey.split(String.valueOf(INTERNAL_SEPARATOR), -1);
        return encode(Arrays.asList(parts));
    }

    static List<String> decode(String storedValue, int expectedCount) {
        if (expectedCount <= 0) {
            throw new IllegalArgumentException("PK 列数必须大于 0");
        }
        if (expectedCount == 1) {
            return List.of(storedValue);
        }
        List<String> values;
        if (storedValue != null && storedValue.stripLeading().startsWith("[")) {
            try {
                values = OBJECT_MAPPER.readValue(storedValue, STRING_LIST);
            } catch (Exception e) {
                throw new IllegalArgumentException("PK 值 JSON 解码失败: " + e.getMessage(), e);
            }
        } else {
            values = Arrays.asList((storedValue == null ? "" : storedValue).split("\\|", -1));
        }
        if (values.size() != expectedCount) {
            throw new IllegalArgumentException(
                    "PK 值列数与任务主键列不匹配: expected=" + expectedCount + " actual=" + values.size()
                            + " pkValue=" + storedValue);
        }
        return List.copyOf(values);
    }
}
