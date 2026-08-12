package com.dfygt.dfetl.server.medical;

import java.util.Arrays;
import java.util.Locale;

/**
 * 医共体时间逻辑类型与表示格式的允许组合。
 *
 * <p>同一个 D8 输入根据逻辑类型可以落为 DATE 或 DATETIME；因此所有源端转换、预检和
 * Doris 类型映射必须按这里的组合规则决策，不能只判断 {@code sdvType}。</p>
 */
public enum MedicalTemporalRule {

    DATE_D8("D", "D8", "DATE"),
    DATETIME_D8("DT", "D8", "DATETIME(6)"),
    DATETIME_DT15("DT", "DT15", "DATETIME(6)");

    private final String sdvType;
    private final String format;
    private final String dorisType;

    MedicalTemporalRule(String sdvType, String format, String dorisType) {
        this.sdvType = sdvType;
        this.format = format;
        this.dorisType = dorisType;
    }

    public String dorisType() {
        return dorisType;
    }

    public static MedicalTemporalRule require(String sdvType, String format) {
        String normalizedType = normalize(sdvType);
        String normalizedFormat = normalize(format);
        return Arrays.stream(values())
                .filter(rule -> rule.sdvType.equals(normalizedType) && rule.format.equals(normalizedFormat))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "不支持的医共体时间契约: sdvType=" + normalizedType + ", format=" + normalizedFormat));
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
    }
}
