package com.dfygt.dfetl.server.medical;

import java.util.List;

/**
 * 批量建表操作结果。
 *
 * @param successCount 成功数
 * @param failedCount  失败数
 * @param failures     失败详情列表
 */
public record ProvisionResult(
        int successCount,
        int failedCount,
        List<FailureDetail> failures
) {}
