package com.dfygt.dfetl.server.medical.precheck;

/**
 * 医共体预检生成选项。
 *
 * @param sampleLimit              每类问题最多取样行数
 * @param maxScanRows              每个预检 SQL 最多扫描的源端行数
 * @param timeoutSeconds           后续执行器执行单个检查项的超时秒数
 * @param duplicatePrimaryKeyCheck 是否生成主键重复检查
 */
public record MedicalPrecheckOptions(
        int sampleLimit,
        int maxScanRows,
        int timeoutSeconds,
        boolean duplicatePrimaryKeyCheck
) {
    public MedicalPrecheckOptions(int sampleLimit, int maxScanRows, boolean duplicatePrimaryKeyCheck) {
        this(sampleLimit, maxScanRows, 15, duplicatePrimaryKeyCheck);
    }

    public static MedicalPrecheckOptions defaults() {
        return new MedicalPrecheckOptions(20, 10000, 15, true);
    }

    public int normalizedSampleLimit() {
        return sampleLimit > 0 ? sampleLimit : defaults().sampleLimit();
    }

    public int normalizedMaxScanRows() {
        return maxScanRows > 0 ? maxScanRows : defaults().maxScanRows();
    }

    public int normalizedTimeoutSeconds() {
        return timeoutSeconds > 0 ? timeoutSeconds : defaults().timeoutSeconds();
    }
}
