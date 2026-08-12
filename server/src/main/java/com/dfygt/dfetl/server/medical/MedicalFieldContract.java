package com.dfygt.dfetl.server.medical;

/**
 * 医共体标准字段契约。
 *
 * @param code        标准字段代码，保持注册表原始大写形式
 * @param name        字段中文名
 * @param sdvType     医共体 SDV 类型
 * @param format      医共体表示格式
 * @param order       标准字段顺序号
 * @param primaryKey  是否规范主键
 * @param notNull     是否规范非空
 * @param dorisColumn Doris 目标列名，统一小写
 * @param dorisType   Doris 目标类型，由医共体规范解析
 * @param valueDomainCode 最终生效值域 ID；字段级 zhiyuid 优先，数据元 zhiyuid 兜底
 */
public record MedicalFieldContract(
        String code,
        String name,
        String sdvType,
        String format,
        Integer order,
        boolean primaryKey,
        boolean notNull,
        String dorisColumn,
        String dorisType,
        String valueDomainCode
) {

    public MedicalFieldContract(
            String code,
            String name,
            String sdvType,
            String format,
            Integer order,
            boolean primaryKey,
            boolean notNull,
            String dorisColumn,
            String dorisType) {
        this(code, name, sdvType, format, order, primaryKey, notNull, dorisColumn, dorisType, null);
    }
}
