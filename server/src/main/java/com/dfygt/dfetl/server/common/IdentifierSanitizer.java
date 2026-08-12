package com.dfygt.dfetl.server.common;

import java.util.regex.Pattern;

/**
 * 数据库标识符（schema/table/column/db）安全校验工具。
 * <p>
 * 用于在动态拼接 DDL/DML SQL 时阻断 SQL 注入：要求标识符仅由
 * {@code [A-Za-z0-9_]} 组成（兼容 MySQL/PostgreSQL/Oracle/SQL Server/Doris 的常见命名规则），
 * 且首字符不能是数字。任何不合规的输入都会被显式拒绝，而不是悄悄替换。
 * <p>
 * 不要用于跨库方言敏感场景（如 Oracle 双引号大小写敏感引用），
 * 仅用于"用户控制 + 反引号包裹 + 拼到 DDL"的本地写入路径。
 */
public final class IdentifierSanitizer {

    /** 允许的标识符正则：字母/下划线开头，后跟字母/数字/下划线，长度 1~128。 */
    private static final Pattern VALID_IDENTIFIER = Pattern.compile("^[A-Za-z_][A-Za-z0-9_]{0,127}$");

    private IdentifierSanitizer() {}

    /**
     * 判定标识符是否合规。
     */
    public static boolean isValid(String identifier) {
        return identifier != null && VALID_IDENTIFIER.matcher(identifier).matches();
    }

    /**
     * 校验并返回标识符；不合规时抛 {@link IllegalArgumentException}，
     * 异常消息包含字段名和原始值（便于排查），但不输出敏感前后文。
     *
     * @param identifier 待校验标识符
     * @param fieldName  字段名，用于错误消息（如 "tgtTable"、"db"）
     * @return 原始合规的标识符
     */
    public static String requireValid(String identifier, String fieldName) {
        if (!isValid(identifier)) {
            throw new IllegalArgumentException(
                    "非法的数据库标识符 [" + fieldName + "]：" + (identifier == null ? "null" : identifier)
                            + "，仅允许 [A-Za-z_][A-Za-z0-9_]{0,127}");
        }
        return identifier;
    }
}
