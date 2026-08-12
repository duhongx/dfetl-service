package com.dfygt.dfetl.server.medical.source;

import com.dfygt.dfetl.server.medical.MedicalNumericRule;
import com.dfygt.dfetl.server.medical.MedicalTemporalTextPolicy;
import org.springframework.stereotype.Component;

/**
 * PostgreSQL 源库方言适配器。
 */
@Component
public class PostgreSqlSourceDialectAdapter implements SourceDialectAdapter {

    @Override
    public String dialect() {
        return "POSTGRESQL";
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
        return "CAST(" + expression + " AS text)";
    }

    @Override
    public String trim(String expression) {
        return "TRIM(" + expression + ")";
    }

    @Override
    public String isBlank(String expression) {
        return "(" + expression + " IS NULL OR TRIM(CAST(" + expression + " AS text)) = '')";
    }

    @Override
    public String regexMatches(String expression, String pattern) {
        return "(" + expression + " ~ '" + escapeLiteral(pattern) + "')";
    }

    @Override
    public String safeDateTime(String expression) {
        return strictTemporal(expression, false);
    }

    @Override
    public String safeDate(String expression) {
        return strictTemporal(expression, true);
    }

    @Override
    public String safeCompactDateTime(String expression) {
        return safeDateTime(expression);
    }

    private String strictTemporal(String expression, boolean dateOnly) {
        String text = trim(castToText(expression));
        String compactDate = strictDateResult(
                text, true, localDateResult(text, "YYYYMMDD", dateOnly));
        String dashedDate = strictDateResult(
                text, false, localDateResult(text, "YYYY-MM-DD", dateOnly));
        String compactDateTime = strictDateTimeResult(
                text, true, compactTimestampResult(text, dateOnly));
        String localDateTime = strictDateTimeResult(
                text, false, localTimestampResult("REPLACE(" + text + ", 'T', ' ')", dateOnly));
        String offsetDateTime = strictDateTimeResult(
                text, false, offsetTimestampResult(text, dateOnly));
        return "CASE"
                + " WHEN " + text + " ~ '" + MedicalTemporalTextPolicy.COMPACT_DATE + "' THEN " + compactDate
                + " WHEN " + text + " ~ '" + MedicalTemporalTextPolicy.DASHED_DATE + "' THEN " + dashedDate
                + " WHEN " + text + " ~ '" + MedicalTemporalTextPolicy.COMPACT_DATETIME + "' THEN " + compactDateTime
                + " WHEN " + text + " ~ '" + MedicalTemporalTextPolicy.LOCAL_DATETIME + "' THEN " + localDateTime
                + " WHEN " + text + " ~ '" + MedicalTemporalTextPolicy.OFFSET_DATETIME + "' THEN " + offsetDateTime
                + " ELSE NULL END";
    }

    private String strictDateResult(String text, boolean compact, String result) {
        int monthStart = compact ? 5 : 6;
        int dayStart = compact ? 7 : 9;
        String format = compact ? "YYYYMMDD" : "YYYY-MM-DD";
        String month = integerPart(text, monthStart, 2);
        String day = integerPart(text, dayStart, 2);
        String year = integerPart(text, 1, 4);
        String basic = year + " BETWEEN 1 AND 9999 AND "
                + month + " BETWEEN 1 AND 12 AND " + day + " BETWEEN 1 AND 31";
        String roundTrip = "TO_CHAR(TO_DATE(" + text + ", '" + format + "'), '" + format + "') = " + text;
        return "CASE WHEN " + basic + " THEN CASE WHEN " + roundTrip
                + " THEN " + result + " ELSE NULL END ELSE NULL END";
    }

    private String strictDateTimeResult(String text, boolean compact, String result) {
        String dateText = compact ? "SUBSTRING(" + text + ", 1, 8)" : "SUBSTRING(" + text + ", 1, 10)";
        String normalized = compact
                ? text
                : "SUBSTRING(REPLACE(" + text + ", 'T', ' '), 1, 19)";
        int hourStart = compact ? 9 : 12;
        int minuteStart = compact ? 11 : 15;
        int secondStart = compact ? 13 : 18;
        String hour = integerPart(text, hourStart, 2);
        String minute = integerPart(text, minuteStart, 2);
        String second = integerPart(text, secondStart, 2);
        String timeBasic = hour + " BETWEEN 0 AND 23 AND "
                + minute + " BETWEEN 0 AND 59 AND " + second + " BETWEEN 0 AND 59";
        String dateValidatedResult = "CASE WHEN " + timeBasic + " THEN CASE WHEN "
                + dateTimeRoundTrip(normalized, compact) + " THEN " + result
                + " ELSE NULL END ELSE NULL END";
        return strictDateResult(dateText, compact, dateValidatedResult);
    }

    private String dateTimeRoundTrip(String normalized, boolean compact) {
        String format = compact ? "YYYYMMDDHH24MISS" : "YYYY-MM-DD HH24:MI:SS";
        return "TO_CHAR(TO_TIMESTAMP(" + normalized + ", '" + format + "'), '" + format + "') = " + normalized;
    }

    private String localDateResult(String text, String format, boolean dateOnly) {
        String date = "TO_DATE(" + text + ", '" + format + "')";
        return dateOnly ? date : "CAST(" + date + " AS timestamp(6))";
    }

    private String localTimestampResult(String text, boolean dateOnly) {
        String timestamp = "CAST(" + text + " AS timestamp(6))";
        return dateOnly ? "CAST(" + timestamp + " AS date)" : timestamp;
    }

    private String compactTimestampResult(String text, boolean dateOnly) {
        String date = "TO_DATE(SUBSTRING(" + text + ", 1, 8), 'YYYYMMDD')";
        if (dateOnly) {
            return date;
        }
        String timestamp = "MAKE_TIMESTAMP("
                + integerPart(text, 1, 4) + ", "
                + integerPart(text, 5, 2) + ", "
                + integerPart(text, 7, 2) + ", "
                + integerPart(text, 9, 2) + ", "
                + integerPart(text, 11, 2) + ", "
                + integerPart(text, 13, 2) + ")";
        return "CAST(" + timestamp + " AS timestamp(6))";
    }

    private String offsetTimestampResult(String text, boolean dateOnly) {
        String timestamp = "CAST(CAST(" + text + " AS timestamptz) AT TIME ZONE '"
                + MedicalTemporalTextPolicy.TARGET_TIME_ZONE + "' AS timestamp(6))";
        return dateOnly ? "CAST(" + timestamp + " AS date)" : timestamp;
    }

    private String integerPart(String text, int start, int length) {
        return "CAST(SUBSTRING(" + text + ", " + start + ", " + length + ") AS integer)";
    }

    @Override
    public String safeDecimal(String expression, MedicalNumericRule rule) {
        String text = trim(castToText(expression));
        return "CASE WHEN " + decimalCapacityPredicate(expression, rule) + " THEN CAST("
                + text + " AS numeric(" + rule.precision() + "," + rule.scale() + ")) ELSE NULL END";
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
