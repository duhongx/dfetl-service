package com.dfygt.dfetl.server.medical;

import java.util.List;

/**
 * 规范数据集完整定义（含字段列表）。
 *
 * @param shujujid  数据集 ID
 * @param shujujdm  数据集代码（表名来源）
 * @param shujujmc  数据集名称
 * @param banben    版本号
 * @param fields    字段定义列表（按 shunxuhao 升序）
 */
public record DatasetDefinition(
        String shujujid,
        String shujujdm,
        String shujujmc,
        String banben,
        List<FieldDefinition> fields
) {}
