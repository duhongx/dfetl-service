package com.dfygt.dfetl.server.common;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

import java.util.ArrayList;
import java.util.List;

/**
 * upsert_keys 列专用转换器：宽容读取（兼容历史 JSON 串 / 逗号分隔串），统一写出 JSON。
 *
 * <p>历史背景：upsertKeys 字段以前是 String 类型，DB 中可能存在以下三种格式：
 * <ul>
 *   <li>JSON 数组字符串：{@code ["id","name"]}</li>
 *   <li>逗号分隔字符串：{@code id,name}</li>
 *   <li>NULL / 空串</li>
 * </ul>
 * 本转换器全部兼容读取；写入时统一为 JSON 数组字符串，便于前后端契约一致。
 */
@Converter
public class UpsertKeysConverter implements AttributeConverter<List<String>, String> {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Override
    public String convertToDatabaseColumn(List<String> attribute) {
        if (attribute == null || attribute.isEmpty()) return null;
        try {
            return MAPPER.writeValueAsString(attribute);
        } catch (Exception e) {
            return null;
        }
    }

    @Override
    public List<String> convertToEntityAttribute(String dbData) {
        if (dbData == null || dbData.isBlank()) return new ArrayList<>();
        String trimmed = dbData.trim();
        // JSON 数组优先
        if (trimmed.startsWith("[")) {
            try {
                return MAPPER.readValue(trimmed, new TypeReference<>() {});
            } catch (Exception ignored) {
                // fallthrough to CSV
            }
        }
        // CSV 兜底
        List<String> out = new ArrayList<>();
        for (String s : trimmed.split(",")) {
            String t = s.trim().replaceAll("^\"|\"$", "");
            if (!t.isEmpty()) out.add(t);
        }
        return out;
    }
}
