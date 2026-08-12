package com.dfygt.dfetl.server.medical.source;

import com.dfygt.dfetl.server.medical.MedicalNumericRule;
import org.springframework.stereotype.Component;

/**
 * MySQL 源库方言适配器。
 */
@Component
public class MySqlSourceDialectAdapter implements SourceDialectAdapter {

    @Override
    public String dialect() {
        return "MYSQL";
    }

    @Override
    public String quoteIdentifier(String name) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("identifier must not be blank");
        }
        return "`" + name.replace("`", "``") + "`";
    }

    @Override
    public String castToText(String expression) {
        return "CAST(" + expression + " AS CHAR)";
    }

    @Override
    public String trim(String expression) {
        return "TRIM(" + expression + ")";
    }

    @Override
    public String isBlank(String expression) {
        return "(" + expression + " IS NULL OR TRIM(CAST(" + expression + " AS CHAR)) = '')";
    }

    @Override
    public String regexMatches(String expression, String pattern) {
        return "(" + expression + " REGEXP '" + escapeLiteral(pattern) + "')";
    }

    @Override
    public String safeDateTime(String expression) {
        String text = trim(castToText(expression));
        return "CASE WHEN " + text + " REGEXP '^\\d{14}$' THEN STR_TO_DATE("
                + text + ", '%Y%m%d%H%i%s') ELSE NULL END";
    }

    @Override
    public String safeDate(String expression) {
        String text = trim(castToText(expression));
        return "CASE WHEN " + text + " REGEXP '^\\d{8}$' THEN STR_TO_DATE("
                + text + ", '%Y%m%d') ELSE NULL END";
    }

    @Override
    public String safeCompactDateTime(String expression) {
        String text = trim(castToText(expression));
        return "CASE WHEN " + text + " REGEXP '^\\d{8}$' THEN CAST(STR_TO_DATE("
                + text + ", '%Y%m%d') AS DATETIME) ELSE NULL END";
    }

    @Override
    public String safeDecimal(String expression, MedicalNumericRule rule) {
        String text = trim(castToText(expression));
        return "CASE WHEN " + decimalCapacityPredicate(expression, rule) + " THEN CAST("
                + text + " AS DECIMAL(" + rule.precision() + "," + rule.scale() + ")) ELSE NULL END";
    }

    @Override
    public String charLength(String expression) {
        return "CHAR_LENGTH(" + expression + ")";
    }

    @Override
    public String byteLength(String expression) {
        return "OCTET_LENGTH(" + expression + ")";
    }

    private static String escapeLiteral(String value) {
        return value == null ? "" : value.replace("'", "''");
    }
}
