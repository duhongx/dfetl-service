package com.dfygt.dfetl.server.medical.source;

import com.dfygt.dfetl.server.medical.MedicalNumericRule;
import org.springframework.stereotype.Component;

/**
 * SQL Server 源库方言适配器。
 */
@Component
public class SqlServerSourceDialectAdapter implements SourceDialectAdapter {

    @Override
    public String dialect() {
        return "SQLSERVER";
    }

    @Override
    public String quoteIdentifier(String name) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("identifier must not be blank");
        }
        return "[" + name.replace("]", "]]") + "]";
    }

    @Override
    public String castToText(String expression) {
        return "CAST(" + expression + " AS varchar(max))";
    }

    @Override
    public String trim(String expression) {
        return "LTRIM(RTRIM(" + expression + "))";
    }

    @Override
    public String isBlank(String expression) {
        return "(" + expression + " IS NULL OR LTRIM(RTRIM(CAST(" + expression + " AS varchar(max)))) = '')";
    }

    @Override
    public String regexMatches(String expression, String pattern) {
        throw new UnsupportedOperationException("SQL Server does not provide a compatible built-in regex operator");
    }

    @Override
    public String safeDateTime(String expression) {
        String text = trim(castToText(expression));
        return "CASE WHEN LEN(" + text + ") = 14 THEN TRY_CONVERT(datetime2, "
                + "CONCAT(SUBSTRING(" + text + ",1,8),' ',SUBSTRING(" + text + ",9,2),':',"
                + "SUBSTRING(" + text + ",11,2),':',SUBSTRING(" + text + ",13,2)), 120) ELSE NULL END";
    }

    @Override
    public String safeDate(String expression) {
        String text = trim(castToText(expression));
        return "CASE WHEN LEN(" + text + ") = 8 THEN TRY_CONVERT(date, " + text + ", 112) ELSE NULL END";
    }

    @Override
    public String safeCompactDateTime(String expression) {
        String text = trim(castToText(expression));
        return "CASE WHEN LEN(" + text + ") = 8 THEN TRY_CONVERT(datetime2, "
                + text + ", 112) ELSE NULL END";
    }

    @Override
    public String lexicalDecimalPredicate(String expression) {
        String text = trim(castToText(expression));
        return text + " <> ''"
                + " AND " + text + " NOT LIKE '%[^0-9.-]%'"
                + " AND " + text + " LIKE '%[0-9]%'"
                + " AND (CHARINDEX('-', " + text + ") = 0 OR CHARINDEX('-', " + text + ") = 1)"
                + " AND LEN(" + text + ") - LEN(REPLACE(" + text + ", '-', '')) <= 1"
                + " AND LEN(" + text + ") - LEN(REPLACE(" + text + ", '.', '')) <= 1"
                + " AND LEFT(" + text + ", 1) <> '.'"
                + " AND " + text + " NOT LIKE '-.%'"
                + " AND RIGHT(" + text + ", 1) <> '.'";
    }

    @Override
    public String decimalCapacityPredicate(String expression, MedicalNumericRule rule) {
        String text = trim(castToText(expression));
        String converted = convertedDecimal(text, rule);
        String dot = "CHARINDEX('.', " + text + ")";
        String extraFraction = "SUBSTRING(" + text + ", " + dot + " + " + (rule.scale() + 1)
                + ", LEN(" + text + "))";
        String exactScale = "(" + dot + " = 0 OR REPLACE(" + extraFraction + ", '0', '') = '')";
        return converted + " IS NOT NULL AND " + lexicalDecimalPredicate(expression) + " AND " + exactScale;
    }

    @Override
    public String safeDecimal(String expression, MedicalNumericRule rule) {
        String text = trim(castToText(expression));
        String converted = convertedDecimal(text, rule);
        return "CASE WHEN " + decimalCapacityPredicate(expression, rule)
                + " THEN " + converted + " ELSE NULL END";
    }

    @Override
    public String charLength(String expression) {
        return "LEN(" + expression + ")";
    }

    @Override
    public String byteLength(String expression) {
        return "DATALENGTH(" + expression + ")";
    }

    private static String convertedDecimal(String text, MedicalNumericRule rule) {
        return "TRY_CONVERT(decimal(" + rule.precision() + "," + rule.scale() + "), " + text + ")";
    }
}
