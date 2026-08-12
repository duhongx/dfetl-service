package com.dfygt.dfetl.server.medical;

/**
 * 医共体日期时间文本的共享输入格式合同。
 *
 * <p>PostgreSQL 正式 Reader 与 Doris 数据预检必须使用同一组表达式，避免同一源值在
 * 两条链路中得到不同结论。正则只限定文本形状；真实日历日期仍由方言 SQL 做严格
 * 往返校验。</p>
 */
public final class MedicalTemporalTextPolicy {

    public static final String COMPACT_DATE = "^[0-9]{8}$";
    public static final String DASHED_DATE = "^[0-9]{4}-[0-9]{2}-[0-9]{2}$";
    public static final String COMPACT_DATETIME = "^[0-9]{14}$";
    public static final String LOCAL_DATETIME =
            "^[0-9]{4}-[0-9]{2}-[0-9]{2}[ T][0-9]{2}:[0-9]{2}:[0-9]{2}([.][0-9]{1,6})?$";
    public static final String OFFSET_DATETIME =
            "^[0-9]{4}-[0-9]{2}-[0-9]{2}[ T][0-9]{2}:[0-9]{2}:[0-9]{2}([.][0-9]{1,6})?"
                    + "(Z|[+-](0[0-9]|1[0-3]):?[0-5][0-9]|[+-]14:?00)$";

    public static final String TARGET_TIME_ZONE = "Asia/Shanghai";

    private MedicalTemporalTextPolicy() {
    }
}
