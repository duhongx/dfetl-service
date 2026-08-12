package com.dfygt.dfetl.server.medical;

/**
 * 规范注册表数据集行（dm_shujuji 查询结果映射）。
 *
 * @param shujujid 数据集 ID
 * @param shujujdm 数据集代码
 * @param shujujmc 数据集名称
 * @param banben   版本号
 * @param zuofeibz 作废标记（0=有效, 1=作废）
 */
public record RegistryDatasetRow(
        String shujujid,
        String shujujdm,
        String shujujmc,
        String banben,
        Integer zuofeibz
) {}
