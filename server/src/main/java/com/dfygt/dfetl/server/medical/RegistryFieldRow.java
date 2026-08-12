package com.dfygt.dfetl.server.medical;

/**
 * 规范注册表字段行（dm_shujujzd 查询结果映射）。
 *
 * @param shujujid  数据集 ID
 * @param ziduandm  字段代码
 * @param ziduanmc  字段名称
 * @param shujulx   SDV_Type 代码
 * @param biaoshigs 表示格式
 * @param shunxuhao 顺序号
 * @param zhujianbz 主键标记（1=主键）
 * @param feikongbz 非空标记（1=非空）
 */
public record RegistryFieldRow(
        String shujujid,
        String ziduandm,
        String ziduanmc,
        String shujulx,
        String biaoshigs,
        Integer shunxuhao,
        Integer zhujianbz,
        Integer feikongbz
) {}
