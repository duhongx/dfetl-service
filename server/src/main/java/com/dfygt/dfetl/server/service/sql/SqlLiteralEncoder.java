package com.dfygt.dfetl.server.service.sql;

import java.util.regex.Pattern;

/**
 * SQL 字面量统一编码工具。
 *
 * <p>把任意输入编码为可直接拼进 SQL 的字面量片段，收口同步 / 校验 / 快照 / 修复四处
 * 此前各自重复的 {@code sqlLiteral} / {@code isNumericLiteral} 实现，消除口径漂移。
 *
 * <p>编码规则（四处现状取并集，行为等价）：
 * <ul>
 *   <li>输入 {@code null} → 返回字符串 {@code "NULL"}（不抛错）；</li>
 *   <li>输入匹配 {@code -?\d+(\.\d+)?}（整数或带正负号的小数）→ 返回原值，不加引号；</li>
 *   <li>否则用单引号包裹，内嵌 {@code '} 翻倍转义为 {@code ''}；</li>
 *   <li>输入含 {@code '\n'} 或 {@code '\r'} 字符 → 抛 {@link IllegalArgumentException}。</li>
 * </ul>
 *
 * <p>全静态、无状态、不依赖 Spring，调用方直接 {@code SqlLiteralEncoder.encode(...)}。
 */
public final class SqlLiteralEncoder {

    /** 数值字面量正则：整数或带正负号的小数。 */
    private static final Pattern NUMERIC = Pattern.compile("-?\\d+(\\.\\d+)?");

    /** 工具类，禁实例化。 */
    private SqlLiteralEncoder() {
    }

    /**
     * 把任意输入编码为 SQL 字面量片段。
     *
     * <p>规则见类注释。注意顺序：先检测换行符抛错，再判断数值 / 字符串。
     *
     * @param value 待编码的原始值，允许为 {@code null}
     * @return SQL 字面量片段
     * @throws IllegalArgumentException 当输入包含 {@code '\n'} 或 {@code '\r'} 换行符时
     */
    public static String encode(String value) {
        if (value == null) {
            return "NULL";
        }
        // 先检测换行符：软删除值不允许跨行，避免 SQL 注入 / 拼接断裂
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '\n' || c == '\r') {
                throw new IllegalArgumentException("soft delete value 不能包含换行符");
            }
        }
        // 数值字面量不加引号；其余用单引号包裹并对内嵌单引号翻倍转义
        if (isNumericLiteral(value)) {
            return value;
        }
        return "'" + value.replace("'", "''") + "'";
    }

    /**
     * 判断值是否为纯数值字面量（整数或带正负号的小数）。
     *
     * @param value 待判断的值
     * @return 当 {@code value} 非空且匹配 {@code -?\d+(\.\d+)?} 时返回 {@code true}，
     *         {@code null} 或空串返回 {@code false}
     */
    private static boolean isNumericLiteral(String value) {
        return value != null && !value.isEmpty() && NUMERIC.matcher(value).matches();
    }
}
