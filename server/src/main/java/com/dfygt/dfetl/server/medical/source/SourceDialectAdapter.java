package com.dfygt.dfetl.server.medical.source;

import com.dfygt.dfetl.server.medical.MedicalNumericRule;
import com.dfygt.dfetl.server.medical.MedicalTemporalRule;

/**
 * 源库方言适配器，用于 contract-driven Reader SQL 和预检 SQL 生成。
 */
public interface SourceDialectAdapter {

    String dialect();

    String quoteIdentifier(String name);

    String castToText(String expression);

    String trim(String expression);

    String isBlank(String expression);

    String regexMatches(String expression, String pattern);

    String safeDateTime(String expression);

    String safeDate(String expression);

    /** 将 8 位紧凑日期解析成午夜时间，用于 DT/D8 契约。 */
    String safeCompactDateTime(String expression);

    default String safeTemporal(String expression, MedicalTemporalRule rule) {
        if (rule == null) {
            throw new IllegalArgumentException("医共体时间转换规则不能为空");
        }
        return switch (rule) {
            case DATE_D8 -> safeDate(expression);
            case DATETIME_D8 -> safeCompactDateTime(expression);
            case DATETIME_DT15 -> safeDateTime(expression);
        };
    }

    /**
     * 判断源值是否为普通十进制文本，不施加目标 DECIMAL 容量限制。
     */
    default String lexicalDecimalPredicate(String expression) {
        String text = trim(castToText(expression));
        return regexMatches(text, MedicalNumericRule.lexicalNumberPattern());
    }

    /**
     * 判断普通十进制文本能否无损写入医共体规则对应的目标 DECIMAL。
     */
    default String decimalCapacityPredicate(String expression, MedicalNumericRule rule) {
        if (rule == null) {
            throw new IllegalArgumentException("医共体数值转换规则不能为空");
        }
        String text = trim(castToText(expression));
        return regexMatches(text, rule.regexPattern());
    }

    String safeDecimal(String expression, MedicalNumericRule rule);

    String charLength(String expression);

    /** 返回源值按当前数据库字符集编码后的字节长度。 */
    String byteLength(String expression);
}
