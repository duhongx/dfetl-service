package com.dfygt.dfetl.server.medical;

/**
 * 医共体 N 字段的逻辑数值规则。
 *
 * <p>按 WS/T 363.1，规范格式中的长度包含整数位、小数点和小数位。
 * {@link MedicalFormatParser} 负责将该表示长度换算为本类保存的整数位数；
 * 逗号后的数字仍表示小数位数。例如 {@code N6,2} 换算为最多 3 位整数、
 * 2 位小数，SQL precision 为 5。</p>
 */
public record MedicalNumericRule(int minIntegerDigits, int integerDigits, int scale) {

    private static final int MAX_CROSS_DIALECT_PRECISION = 38;
    private static final String LEXICAL_NUMBER_PATTERN = "^-?[0-9]+([.][0-9]+)?$";

    public MedicalNumericRule {
        if (minIntegerDigits <= 0) {
            throw new IllegalArgumentException("医共体数值最小整数位数必须大于 0");
        }
        if (integerDigits <= 0) {
            throw new IllegalArgumentException("医共体数值最大整数位数必须大于 0");
        }
        if (minIntegerDigits > integerDigits) {
            throw new IllegalArgumentException("医共体数值最小整数位数不能大于最大整数位数");
        }
        if (scale < 0) {
            throw new IllegalArgumentException("医共体数值小数位数不能小于 0");
        }
        if (integerDigits + scale > MAX_CROSS_DIALECT_PRECISION) {
            throw new IllegalArgumentException(
                    "医共体数值总精度不能超过 " + MAX_CROSS_DIALECT_PRECISION);
        }
    }

    public int precision() {
        return integerDigits + scale;
    }

    /**
     * 返回 PostgreSQL、MySQL 和 Oracle 均可使用的完整匹配表达式。
     *
     * <p>允许不影响数值精度的前导零和尾随零，但禁止隐式四舍五入。</p>
     */
    public String regexPattern() {
        // 范围下界作为契约元数据保留；源值转换按上界做无损门禁，避免通过补零改变业务值。
        String integerPart = "0*[0-9]{1," + integerDigits + "}";
        if (scale == 0) {
            return "^-?" + integerPart + "([.]0+)?$";
        }
        return "^-?" + integerPart + "([.][0-9]{1," + scale + "}0*)?$";
    }

    /** 仅判断是否为普通十进制文本，不施加目标 DECIMAL 容量门禁。 */
    public static String lexicalNumberPattern() {
        return LEXICAL_NUMBER_PATTERN;
    }

    public static MedicalNumericRule require(String sdvType, String format) {
        return MedicalFormatParser.requireNumeric(sdvType, format);
    }
}
