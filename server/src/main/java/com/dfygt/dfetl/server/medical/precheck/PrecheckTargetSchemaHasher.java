package com.dfygt.dfetl.server.medical.precheck;

import com.dfygt.dfetl.server.medical.quality.TargetWriteContract;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Comparator;
import java.util.Locale;

/** 预检运行和启动门禁共用的 Doris 目标物理结构摘要算法。 */
public final class PrecheckTargetSchemaHasher {

    private PrecheckTargetSchemaHasher() {
    }

    public static String hash(TargetWriteContract contract) {
        if (contract == null || contract.columns() == null || contract.columns().isEmpty()) {
            throw new IllegalStateException("Doris 目标写入合同不能为空");
        }
        StringBuilder canonical = new StringBuilder()
                .append(text(contract.database())).append('|')
                .append(text(contract.table())).append('\n');
        contract.columns().values().stream()
                .sorted(Comparator.comparing(column -> text(column.name()).toLowerCase(Locale.ROOT)))
                .forEach(column -> canonical
                        .append(text(column.name()).toLowerCase(Locale.ROOT)).append('|')
                        .append(text(column.dataType()).toUpperCase(Locale.ROOT)).append('|')
                        .append(column.characterCapacity()).append('|')
                        .append(column.numericPrecision()).append('|')
                        .append(column.numericScale()).append('|')
                        .append(column.datetimePrecision()).append('|')
                        .append(column.nullable()).append('\n'));
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256")
                    .digest(canonical.toString().getBytes(StandardCharsets.UTF_8));
            StringBuilder hash = new StringBuilder(bytes.length * 2);
            for (byte value : bytes) {
                hash.append(String.format("%02x", value));
            }
            return hash.toString();
        } catch (Exception e) {
            throw new IllegalStateException("生成 Doris 目标结构摘要失败", e);
        }
    }

    private static String text(String value) {
        return value == null ? "" : value.trim();
    }
}
