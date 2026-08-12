package com.dfygt.dfetl.server.medical;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 医共体表示格式的统一解析入口。
 *
 * <p>当前数值链路支持 {@code N..P[,S]}、{@code NP[,S]} 和完整范围
 * {@code NMIN..MAX[,S]}。P/MIN/MAX 均为包含小数点和小数位的表示长度；
 * 所有需要解释 N 格式的调用方必须经过本类。</p>
 */
public final class MedicalFormatParser {

    private static final Pattern NUMERIC_FORMAT = Pattern.compile(
            "^N(?:(\\d+)\\.\\.(\\d+)|\\.\\.(\\d+)|(\\d+))(?:,(\\d+))?$",
            Pattern.CASE_INSENSITIVE);

    private MedicalFormatParser() {
    }

    public static MedicalNumericRule requireNumeric(String sdvType, String format) {
        String normalizedType = sdvType == null ? "" : sdvType.trim().toUpperCase(Locale.ROOT);
        if (!"N".equals(normalizedType)) {
            throw new MedicalFormatException(null, sdvType, format,
                    "医共体数值规则只支持 N 类型: " + sdvType);
        }

        String normalizedFormat = format == null ? "" : format.trim();
        Matcher matcher = NUMERIC_FORMAT.matcher(normalizedFormat);
        if (!matcher.matches()) {
            throw new MedicalFormatException(null, sdvType, format,
                    "不支持的医共体数值格式: " + format);
        }

        int minDisplayLength;
        int maxDisplayLength;
        int scale;
        try {
            if (matcher.group(1) != null) {
                minDisplayLength = Integer.parseInt(matcher.group(1));
                maxDisplayLength = Integer.parseInt(matcher.group(2));
            } else {
                minDisplayLength = 1;
                String upperBound = matcher.group(3) != null ? matcher.group(3) : matcher.group(4);
                maxDisplayLength = Integer.parseInt(upperBound);
            }
            scale = matcher.group(5) == null ? 0 : Integer.parseInt(matcher.group(5));
        } catch (NumberFormatException ex) {
            throw new MedicalFormatException(null, sdvType, format,
                    "不支持的医共体数值格式（位数超出整数范围）: " + format);
        }

        if (minDisplayLength > maxDisplayLength) {
            throw new MedicalFormatException(null, sdvType, format,
                    "医共体数值格式最小表示长度不能大于最大表示长度: " + format);
        }
        int decimalPointLength = scale > 0 ? 1 : 0;
        int maxIntegerDigits = maxDisplayLength - scale - decimalPointLength;
        if (maxIntegerDigits <= 0) {
            throw new MedicalFormatException(null, sdvType, format,
                    "医共体数值格式没有可用整数位: " + format);
        }
        // 范围下界只作为契约元数据保留；转换不通过补零强制最小显示长度。
        int minIntegerDigits = Math.max(1, minDisplayLength - scale - decimalPointLength);
        try {
            return new MedicalNumericRule(minIntegerDigits, maxIntegerDigits, scale);
        } catch (IllegalArgumentException ex) {
            throw new MedicalFormatException(null, sdvType, format,
                    ex.getMessage() + ": " + format);
        }
    }
}
