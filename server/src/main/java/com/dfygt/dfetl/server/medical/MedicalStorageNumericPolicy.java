package com.dfygt.dfetl.server.medical;

/**
 * 医共体 N 字段的物理采集容量策略。
 *
 * <p>当前严格预检和新建 Doris 表均按医共体最新定义直接落地，不再隐式扩容。
 * 保留本类作为历史调用入口，避免 DDL、Reader 和预检链路出现多套数值解析入口。</p>
 */
public final class MedicalStorageNumericPolicy {

    private MedicalStorageNumericPolicy() {
    }

    public static MedicalNumericRule require(String sdvType, String format) {
        return MedicalFormatParser.requireNumeric(sdvType, format);
    }
}
