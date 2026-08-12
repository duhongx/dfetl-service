package com.dfygt.dfetl.server.medical.source;

import com.dfygt.dfetl.server.medical.MedicalNumericRule;
import org.springframework.stereotype.Component;

/**
 * Oracle 源库方言适配器。
 */
@Component
public class OracleSourceDialectAdapter implements SourceDialectAdapter {

    @Override
    public String dialect() {
        return "ORACLE";
    }

    @Override
    public String quoteIdentifier(String name) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("identifier must not be blank");
        }
        return "\"" + name.replace("\"", "\"\"") + "\"";
    }

    @Override
    public String castToText(String expression) {
        return "TO_CHAR(" + expression + ")";
    }

    @Override
    public String trim(String expression) {
        return "TRIM(" + expression + ")";
    }

    @Override
    public String isBlank(String expression) {
        return "(" + expression + " IS NULL OR TRIM(TO_CHAR(" + expression + ")) IS NULL)";
    }

    @Override
    public String regexMatches(String expression, String pattern) {
        return "REGEXP_LIKE(" + expression + ", '" + escapeLiteral(pattern) + "')";
    }

    @Override
    public String safeDateTime(String expression) {
        String text = trim(castToText(expression));
        return "CASE WHEN REGEXP_LIKE(" + text + ", '^\\d{14}$') THEN TO_DATE("
                + text + ", 'YYYYMMDDHH24MISS') ELSE NULL END";
    }

    @Override
    public String safeDate(String expression) {
        String text = trim(castToText(expression));
        return "CASE WHEN REGEXP_LIKE(" + text + ", '^\\d{8}$') THEN TO_DATE("
                + text + ", 'YYYYMMDD') ELSE NULL END";
    }

    @Override
    public String safeCompactDateTime(String expression) {
        String text = trim(castToText(expression));
        return "CASE WHEN REGEXP_LIKE(" + text + ", '^\\d{8}$') THEN TO_TIMESTAMP("
                + text + ", 'YYYYMMDD') ELSE NULL END";
    }

    @Override
    public String safeDecimal(String expression, MedicalNumericRule rule) {
        String text = trim(castToText(expression));
        return "CASE WHEN " + decimalCapacityPredicate(expression, rule) + " THEN CAST(TO_NUMBER("
                + text + ") AS NUMBER(" + rule.precision() + "," + rule.scale() + ")) ELSE NULL END";
    }

    @Override
    public String charLength(String expression) {
        return "LENGTH(" + expression + ")";
    }

    @Override
    public String byteLength(String expression) {
        return "LENGTHB(" + expression + ")";
    }

    private static String escapeLiteral(String value) {
        return value == null ? "" : value.replace("'", "''");
    }
}
