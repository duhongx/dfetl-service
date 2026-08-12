package com.dfygt.dfetl.server.medical;

/**
 * 失败详情记录。
 *
 * @param shujujdm     数据集代码（或视图名）
 * @param errorMessage 错误原因
 */
public record FailureDetail(
        String shujujdm,
        String errorMessage
) {}
