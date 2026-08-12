package com.dfygt.dfetl.server.medical;

/**
 * 规范数据集摘要信息（用于前端列表页）。
 *
 * @param shujujid    数据集 ID
 * @param shujujdm    数据集代码
 * @param shujujmc    数据集名称
 * @param fieldCount  字段总数
 * @param pkCount     主键列数量（zhujianbz=1 的字段数）
 * @param hasZuofeibz 是否含 ZUOFEIBZ 字段
 */
public record DatasetSummary(
        String shujujid,
        String shujujdm,
        String shujujmc,
        int fieldCount,
        int pkCount,
        boolean hasZuofeibz
) {}
