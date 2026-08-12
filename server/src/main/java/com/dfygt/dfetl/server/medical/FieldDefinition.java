package com.dfygt.dfetl.server.medical;

/**
 * 医共体标准字段当前定义。
 *
 * @param ziduanid       医共体字段 ID（dm_shujujzd.ziduanid）
 * @param ziduandm       字段代码
 * @param ziduanmc       字段名称
 * @param sdvType        SDV 类型代码
 * @param biaoshigs      标准格式
 * @param shunxuhao      顺序号
 * @param primaryKey     主键标志
 * @param notNull        必填标志
 * @param valueDomainCode 最终生效值域 ID
 */
public record FieldDefinition(
        String ziduanid,
        String ziduandm,
        String ziduanmc,
        String sdvType,
        String biaoshigs,
        Integer shunxuhao,
        boolean primaryKey,
        boolean notNull,
        String valueDomainCode
) {

    public FieldDefinition(
            String ziduandm,
            String ziduanmc,
            String sdvType,
            String biaoshigs,
            Integer shunxuhao,
            boolean primaryKey,
            boolean notNull) {
        this(null, ziduandm, ziduanmc, sdvType, biaoshigs, shunxuhao, primaryKey, notNull, null);
    }

    public FieldDefinition(
            String ziduandm,
            String ziduanmc,
            String sdvType,
            String biaoshigs,
            Integer shunxuhao,
            boolean primaryKey,
            boolean notNull,
            String valueDomainCode) {
        this(null, ziduandm, ziduanmc, sdvType, biaoshigs, shunxuhao, primaryKey, notNull, valueDomainCode);
    }
}
