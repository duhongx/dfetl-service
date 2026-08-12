package com.dfygt.dfetl.server.engine.checksum;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Spec 023：行字段标准化（PRD 3.2）。
 *
 * <p>统一规则（必须双端一致）：
 * <ul>
 *   <li>NULL → 专用哨兵值，必须与空字符串区分</li>
 *   <li>String → 保留原始空白；仅对完整日期/时间字面量做跨驱动归一化</li>
 *   <li>Boolean → "1"/"0"</li>
 *   <li>Number 整形 → toString（去掉前导零、不带小数点）</li>
 *   <li>Number 浮点 → 默认保留 6 位有效数字（HALF_UP）</li>
 *   <li>BigDecimal → stripTrailingZeros + toPlainString</li>
 *   <li>时间类 → 统一 yyyy-MM-dd HH:mm:ss（系统时区 Asia/Shanghai）</li>
 *   <li>byte[] → 带类型前缀的十六进制字符串；其它 → toString</li>
 * </ul>
 *
 * <p>不依赖 Spring，可纯单元测试。
 */
public final class RowNormalizer {

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final DateTimeFormatter TS_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    /** 列分隔符（行内）。选用单字节不可见控制符以避免与字段值冲突。 */
    public static final String COL_SEP = "\u0001";
    /** NULL 哨兵；用于避免 NULL 与空字符串在 checksum 中碰撞。 */
    public static final String NULL_SENTINEL = "\u0002NULL";
    private static final String ESCAPED_STRING_PREFIX = "\u0002STR:";
    private static final String BYTES_PREFIX = "\u0002BYTES:";

    /**
     * Spec 057 时间归一化补丁：识别字符串形式的日期/时间，避免跨数据源驱动差异引发假 DIFF。
     *
     * <p>背景：{@code rs.getObject(i)} 在不同 JDBC driver 下返回类型不一致（Oracle→{@code Timestamp}，
     * 某些 Doris/PG 配置→{@code String}）。{@code Timestamp.toString()} 输出 {@code "2026-05-18 10:25:49.0"}，
     * {@code LocalDateTime.toString()} 输出 {@code "2026-05-18T10:25:49"}，
     * 而 driver 直接返回 {@code String} 时则按数据库字面量给。
     * 三者业务上等价但字符串不同 → 双端 hash 不一致 → 校验误判。
     *
     * <p>解决：在归一化阶段对所有看起来像日期时间的字符串做格式识别和重格式化，统一输出 {@code yyyy-MM-dd HH:mm:ss}。
     * 兼容样本：
     * <ul>
     *   <li>{@code 2026-05-18 10:25:49}</li>
     *   <li>{@code 2026-05-18 10:25:49.0}</li>
     *   <li>{@code 2026-05-18 10:25:49.123456}</li>
     *   <li>{@code 2026-05-18T10:25:49}</li>
     *   <li>{@code 2026-05-18T10:25:49.000Z}</li>
     *   <li>{@code 2026-05-18T10:25:49+08:00}</li>
     *   <li>{@code 2026-05-18}（仅日期，统一补 00:00:00）</li>
     * </ul>
     * <p>不匹配的字符串原样返回，零误伤。
     */
    private static final Pattern DT_LIKE_PATTERN = Pattern.compile(
            "^\\d{4}-\\d{2}-\\d{2}([T ]\\d{2}:\\d{2}:\\d{2}(\\.\\d{1,9})?(Z|[+-]\\d{2}:?\\d{2})?)?$"
    );

    private final int floatScale;

    public RowNormalizer() {
        this(6);
    }

    public RowNormalizer(int floatScale) {
        if (floatScale < 0 || floatScale > 18) {
            throw new IllegalArgumentException("floatScale 必须在 [0, 18]");
        }
        this.floatScale = floatScale;
    }

    /** 单值标准化。 */
    public String normalize(Object v) {
        if (v == null) return NULL_SENTINEL;
        if (v instanceof String s) {
            // Spec 057：String 形式的日期时间也统一归一化（解决跨数据源驱动差异）
            String dt = tryNormalizeDateTimeString(s);
            return escapeReservedString(dt != null ? dt : s);
        }
        if (v instanceof Boolean b) return b ? "1" : "0";
        if (v instanceof BigDecimal bd) {
            return formatNumeric(bd);
        }
        if (v instanceof Float f) {
            return formatNumeric(BigDecimal.valueOf(f.doubleValue()));
        }
        if (v instanceof Double d) {
            return formatNumeric(BigDecimal.valueOf(d));
        }
        if (v instanceof Number n) {
            // 整形（含 BigInteger / Long / Integer / Short / Byte）
            return n.toString();
        }
        if (v instanceof java.sql.Timestamp ts) {
            return ts.toLocalDateTime().format(TS_FMT);
        }
        if (v instanceof java.sql.Date sd) {
            // Oracle DATE 列经 JDBC 返回 java.sql.Timestamp（含时分秒），
            // 而 Doris/MySQL DATE 列返回 java.sql.Date（仅日期）。
            // 统一用 TS_FMT 追加 00:00:00，避免同一数据因驱动差异产生 hash 不一致。
            return sd.toLocalDate().atStartOfDay().format(TS_FMT);
        }
        if (v instanceof java.util.Date ud) {
            return LocalDateTime.ofInstant(ud.toInstant(), ZONE).format(TS_FMT);
        }
        if (v instanceof Instant in) {
            return LocalDateTime.ofInstant(in, ZONE).format(TS_FMT);
        }
        if (v instanceof OffsetDateTime od) {
            return od.atZoneSameInstant(ZONE).toLocalDateTime().format(TS_FMT);
        }
        if (v instanceof LocalDateTime ld) {
            return ld.format(TS_FMT);
        }
        if (v instanceof LocalDate ldate) {
            return ldate.format(DATE_FMT);
        }
        if (v instanceof byte[] bytes) {
            return BYTES_PREFIX + bytesToHex(bytes);
        }
        return v.toString();
    }

    /** 行级拼接：按列顺序 normalize 后用 {@link #COL_SEP} 连接。 */
    public String normalizeRow(List<?> values) {
        if (values == null || values.isEmpty()) return "";
        StringBuilder sb = new StringBuilder(values.size() * 16);
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) sb.append(COL_SEP);
            sb.append(normalize(values.get(i)));
        }
        return sb.toString();
    }

    public String normalizeRow(Object... values) {
        return normalizeRow(Arrays.asList(values));
    }

    /**
     * 数值归一化统一流程：BigDecimal / Float / Double 共用。
     *
     * <p>规则（按顺序）：
     * <ol>
     *   <li>零值返回 "0"（防 BigDecimal("0E-18") 或 0.0 输出科学计数）</li>
     *   <li>极小值保护：|bd| &lt; 0.5 × 10^-floatScale 时归零（如 floatScale=6 时 1e-7 → "0"）。
     *       注意 {@link MathContext} 的 precision 是有效数字位数而非小数位数，
     *       1e-7 只有 1 位有效数字 ≤ 6，round 不会让它归零，必须显式判断。</li>
     *   <li>round(MathContext(floatScale, HALF_UP)) 截断到 floatScale 位有效数字</li>
     *   <li>截断后若变零，同样返回 "0"（保险，正常不会触发）</li>
     *   <li>stripTrailingZeros 去除尾随零（让 100.00 → 100、1.50 → 1.5）</li>
     *   <li>修正 stripTrailingZeros 产生的负 scale（如 BigDecimal("100").stripTrailingZeros() scale=-2）</li>
     *   <li>toPlainString 输出，永不产生科学计数法</li>
     * </ol>
     */
    private String formatNumeric(BigDecimal bd) {
        if (bd.signum() == 0) {
            return "0";
        }
        // 极小值保护：|bd| < 5×10^-(floatScale+1) 视为零
        // （MathContext 是有效数字位数，对 1e-7 这种"绝对值小但有效数字仅 1 位"的值无能为力）
        BigDecimal tinyThreshold = new BigDecimal("5e-" + (floatScale + 1));
        if (bd.abs().compareTo(tinyThreshold) < 0) {
            return "0";
        }
        BigDecimal rounded = bd.round(new java.math.MathContext(floatScale, RoundingMode.HALF_UP));
        if (rounded.signum() == 0) {
            return "0";
        }
        BigDecimal stripped = rounded.stripTrailingZeros();
        if (stripped.scale() < 0) {
            stripped = stripped.setScale(0);
        }
        return stripped.toPlainString();
    }

    private static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) sb.append(String.format("%02x", b));
        return sb.toString();
    }

    private static String escapeReservedString(String s) {
        if (s == null) {
            return NULL_SENTINEL;
        }
        if (s.startsWith("\u0002") || s.contains(COL_SEP)) {
            return ESCAPED_STRING_PREFIX
                    + Base64.getEncoder().encodeToString(s.getBytes(StandardCharsets.UTF_8));
        }
        return s;
    }

    /**
     * Spec 057：尝试将字符串识别为日期/时间，并归一化为 {@code yyyy-MM-dd HH:mm:ss}。
     * 默认精度=秒（截断毫秒及以下）；时区按 {@link #ZONE} 统一为 Asia/Shanghai。
     *
     * @return 归一化后的字符串；若不匹配则返回 {@code null}（调用方原样返回 trim 后的串）
     */
    static String tryNormalizeDateTimeString(String s) {
        if (s == null || s.isEmpty()) return null;
        if (!DT_LIKE_PATTERN.matcher(s).matches()) return null;
        try {
            // 仅日期：补 00:00:00
            if (s.length() == 10) {
                return LocalDate.parse(s, DATE_FMT).atStartOfDay().format(TS_FMT);
            }
            // 含偏移/Z：按 OffsetDateTime 解析后转 Asia/Shanghai
            if (s.endsWith("Z") || s.matches(".*[+-]\\d{2}:?\\d{2}$")) {
                String iso = s.replace(' ', 'T');
                // OffsetDateTime.parse 不识别紧凑偏移 +0800，统一规范化为 +08:00
                iso = iso.replaceAll("([+-]\\d{2})(\\d{2})$", "$1:$2");
                OffsetDateTime od = OffsetDateTime.parse(iso);
                return od.atZoneSameInstant(ZONE).toLocalDateTime().format(TS_FMT);
            }
            // 本地时间：替换 'T' 与 ' '；去掉 .xxx 小数秒（精度=秒）
            String body = s.replace('T', ' ');
            int dotIdx = body.indexOf('.');
            if (dotIdx > 0) body = body.substring(0, dotIdx);
            return LocalDateTime.parse(body.replace(' ', 'T')).format(TS_FMT);
        } catch (Exception ignore) {
            return null;
        }
    }
}
