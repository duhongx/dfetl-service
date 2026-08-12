package com.dfygt.dfetl.server.medical;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 将规范定义的 SDV 类型代码 + biaoshigs 格式映射为 Doris 列类型。
 *
 * <p>映射规则参照医共体规范注册表类型体系：S1/S2/S3/N/D/DT/L/BY 等。</p>
 */
@Component
public class SdvTypeMappingPolicy {

    private static final Logger log = LoggerFactory.getLogger(SdvTypeMappingPolicy.class);

    /** Doris VARCHAR 最大长度 */
    private static final int MAX_VARCHAR_LENGTH = 65533;

    /** 医共体 AN..1000 及以上这类字段按大文本处理，避免真实 HTML/病历文本撑爆 VARCHAR。 */
    private static final int LARGE_TEXT_DECLARED_LENGTH = 1000;

    /** 未识别类型的默认回退 */
    private static final String FALLBACK_TYPE = "VARCHAR(256)";

    // TODO: 自定义规则优先级 — 后续接入 DorisTypeMappingRuleService，
    //  当存在匹配的自定义类型映射规则时优先使用自定义规则覆盖默认映射。

    // ── 格式解析正则 ──
    /** AN..50 格式（变长字母数字） */
    private static final Pattern PATTERN_AN_DOTDOT = Pattern.compile("^AN\\.\\.(\\d+)$", Pattern.CASE_INSENSITIVE);
    /** A..50 格式（变长汉字） */
    private static final Pattern PATTERN_A_DOTDOT = Pattern.compile("^A\\.\\.(\\d+)$", Pattern.CASE_INSENSITIVE);
    /** AN6 格式（固定长度字母数字） */
    private static final Pattern PATTERN_AN_FIXED = Pattern.compile("^AN(\\d+)$", Pattern.CASE_INSENSITIVE);
    /** A6 格式（固定长度汉字） */
    private static final Pattern PATTERN_A_FIXED = Pattern.compile("^A(\\d+)$", Pattern.CASE_INSENSITIVE);
    /** N..15,3 或 N..15 格式 */
    private static final Pattern PATTERN_N_DOTDOT = Pattern.compile("^N\\.\\.(\\d+)(?:,(\\d+))?$", Pattern.CASE_INSENSITIVE);
    /** N..6,2 格式（变长带小数） */
    private static final Pattern PATTERN_N_DOTDOT_DECIMAL = Pattern.compile("^N\\.\\.(\\d+),(\\d+)$", Pattern.CASE_INSENSITIVE);
    /** N6 或 N6,2 格式（固定宽度） */
    private static final Pattern PATTERN_N_FIXED = Pattern.compile("^N(\\d+)(?:,(\\d+))?$", Pattern.CASE_INSENSITIVE);

    /**
     * 解析 biaoshigs 格式获取长度/精度信息。
     */
    public record FormatDescriptor(
            boolean alphanumeric,
            boolean alpha,
            boolean numeric,
            boolean fixedWidth,
            Integer length,
            Integer scale
    ) {}

    /**
     * 将 SDV 类型代码 + biaoshigs 格式映射为 Doris 列类型。
     *
     * @param sdvType   SDV 类型代码 (S1/S2/S3/N/D/DT/L/BY)
     * @param biaoshigs 表示格式 (AN..50, N6, N..15,3 等)
     * @return Doris 类型字符串（如 VARCHAR(150), INT, DECIMAL(18,3)）
     */
    public String mapToDorisType(String sdvType, String biaoshigs) {
        return mapToDorisType(sdvType, biaoshigs, false);
    }

    /**
     * 将 SDV 类型和字段角色映射为 Doris 列类型。
     *
     * <p>大文本普通列允许使用 STRING；Doris key 不支持 STRING，因此有明确有限长度的
     * 大文本主键按医共体 UTF-8 长度规则映射为 VARCHAR。</p>
     *
     * @param sdvType   SDV 类型代码
     * @param biaoshigs 表示格式
     * @param primaryKey 是否为 Doris key 对应的规范主键
     * @return Doris 类型字符串
     */
    public String mapToDorisType(String sdvType, String biaoshigs, boolean primaryKey) {
        if (sdvType == null || sdvType.isBlank()) {
            log.warn("[MedicalRegistry] SDV类型为空，回退 {}", FALLBACK_TYPE);
            return FALLBACK_TYPE;
        }

        String type = sdvType.trim().toUpperCase();
        FormatDescriptor fmt = parseFormat(biaoshigs);

        String mappedType = switch (type) {
            case "S1" -> mapS1(fmt, biaoshigs);
            case "S2" -> mapS2(fmt, biaoshigs);
            case "S3" -> mapS3(fmt, biaoshigs);
            case "N" -> mapN(type, biaoshigs);
            case "D", "DT" -> MedicalTemporalRule.require(type, biaoshigs).dorisType();
            // 医共体当前 L/BY 均按文本透传；显式列出，避免被当作未知类型静默回退。
            case "L", "BY" -> FALLBACK_TYPE;
            default -> {
                log.warn("[MedicalRegistry] SDV类型 {} (biaoshigs={}) 未识别，回退 {}", sdvType, biaoshigs, FALLBACK_TYPE);
                yield FALLBACK_TYPE;
            }
        };
        if (primaryKey && "STRING".equalsIgnoreCase(mappedType)) {
            return varcharForMedicalKey(fmt, sdvType, biaoshigs);
        }
        return mappedType;
    }

    /**
     * 解析 biaoshigs 格式。
     *
     * <p>支持字符串格式以及供 S1/S2/S3 使用的简单 N 格式。真正的 N 逻辑类型由
     * {@link MedicalFormatParser} 统一解析。</p>
     */
    public FormatDescriptor parseFormat(String biaoshigs) {
        if (biaoshigs == null || biaoshigs.isBlank()) {
            return new FormatDescriptor(false, false, false, false, null, null);
        }

        String s = biaoshigs.trim();

        // AN..N 格式（变长字母数字）
        Matcher m = PATTERN_AN_DOTDOT.matcher(s);
        if (m.matches()) {
            return new FormatDescriptor(true, false, false, false, Integer.parseInt(m.group(1)), null);
        }

        // A..N 格式（变长汉字）
        m = PATTERN_A_DOTDOT.matcher(s);
        if (m.matches()) {
            return new FormatDescriptor(false, true, false, false, Integer.parseInt(m.group(1)), null);
        }

        // ANx 格式（固定长度字母数字，如 AN6）
        m = PATTERN_AN_FIXED.matcher(s);
        if (m.matches()) {
            return new FormatDescriptor(true, false, false, true, Integer.parseInt(m.group(1)), null);
        }

        // Ax 格式（固定长度汉字，如 A6）
        m = PATTERN_A_FIXED.matcher(s);
        if (m.matches()) {
            return new FormatDescriptor(false, true, false, true, Integer.parseInt(m.group(1)), null);
        }

        // N..P,S 或 N..P 格式
        m = PATTERN_N_DOTDOT.matcher(s);
        if (m.matches()) {
            Integer scale = m.group(2) != null ? Integer.parseInt(m.group(2)) : null;
            return new FormatDescriptor(false, false, true, false, Integer.parseInt(m.group(1)), scale);
        }

        // Nx 或 Nx,S 格式（固定宽度）
        m = PATTERN_N_FIXED.matcher(s);
        if (m.matches()) {
            Integer scale = m.group(2) != null ? Integer.parseInt(m.group(2)) : null;
            return new FormatDescriptor(false, false, true, true, Integer.parseInt(m.group(1)), scale);
        }

        return new FormatDescriptor(false, false, false, false, null, null);
    }

    // ── S1: 字符串语义 ──

    private String mapS1(FormatDescriptor fmt, String biaoshigs) {
        if (fmt.alphanumeric() && fmt.length() != null) {
            return varcharForMedicalString(fmt.length());
        }
        if (fmt.alpha() && fmt.length() != null) {
            return varcharForMedicalString(fmt.length());
        }
        if (fmt.numeric() && fmt.length() != null) {
            // S1 是字符串语义，少量规范字段使用 N..n 表示内容形态（如显示顺序）。
            // 这里保留字符串存储，并按医共体 Doris 长度规则放大。
            return varcharForMedicalString(fmt.length());
        }
        // S1 + 固定宽度也按 alphanumeric/alpha 处理（fixedWidth=true 时 length 已解析）
        if (fmt.fixedWidth() && fmt.length() != null) {
            return varcharForMedicalString(fmt.length());
        }
        // 无法解析时回退
        log.warn("[MedicalRegistry] S1 类型 biaoshigs={} 无法解析长度，回退 {}", biaoshigs, FALLBACK_TYPE);
        return FALLBACK_TYPE;
    }

    // ── S2: 固定位数数字编码（仍按 VARCHAR 存储，避免业务侧字符串/数字比较踩坑） ──

    private String mapS2(FormatDescriptor fmt, String biaoshigs) {
        if (fmt.numeric() && fmt.fixedWidth() && fmt.length() != null) {
            // 医疗规范中 S2 通常是分类代码（如 RH 血型 0/1/2），
            // 业务侧多以字符串方式比较，此处统一用 VARCHAR 保持兼容。
            return varcharForMedicalString(fmt.length());
        }
        // S2 也可能是 AN.. / AN 格式
        if (fmt.alphanumeric() && fmt.length() != null) {
            return varcharForMedicalString(fmt.length());
        }
        log.warn("[MedicalRegistry] S2 类型 biaoshigs={} 无法解析，回退 {}", biaoshigs, FALLBACK_TYPE);
        return FALLBACK_TYPE;
    }

    // ── S3: 同 S1 ──

    private String mapS3(FormatDescriptor fmt, String biaoshigs) {
        if (fmt.alphanumeric() && fmt.length() != null) {
            return varcharForMedicalString(fmt.length());
        }
        if (fmt.alpha() && fmt.length() != null) {
            return varcharForMedicalString(fmt.length());
        }
        // S3 + 固定宽度数字格式（如 N1, N2）→ 保留字符串语义并按 Doris 字节长度放大
        if (fmt.numeric() && fmt.fixedWidth() && fmt.length() != null) {
            return varcharForMedicalString(fmt.length());
        }
        // S3 + 固定宽度 AN/A 格式
        if (fmt.fixedWidth() && fmt.length() != null) {
            return varcharForMedicalString(fmt.length());
        }
        log.warn("[MedicalRegistry] S3 类型 biaoshigs={} 无法解析长度，回退 {}", biaoshigs, FALLBACK_TYPE);
        return FALLBACK_TYPE;
    }

    // ── N: 数值类型 ──

    private String mapN(String sdvType, String biaoshigs) {
        MedicalNumericRule rule = MedicalStorageNumericPolicy.require(sdvType, biaoshigs);
        if (rule.scale() > 0) {
            // 物理采集容量已在医共体逻辑定义上增加整数位和小数位缓冲。
            return "DECIMAL(" + rule.precision() + "," + rule.scale() + ")";
        }
        if (rule.integerDigits() <= 9) {
            return "INT";
        }
        if (rule.integerDigits() <= 18) {
            return "BIGINT";
        }
        return "DECIMAL(" + rule.integerDigits() + ",0)";
    }

    // ── 工具方法 ──

    private String varcharForMedicalString(int declaredLength) {
        if (declaredLength >= LARGE_TEXT_DECLARED_LENGTH) {
            return "STRING";
        }
        return varcharCapped((long) declaredLength * 3);
    }

    private String varcharForMedicalKey(FormatDescriptor fmt, String sdvType, String biaoshigs) {
        if (fmt.length() == null) {
            throw new IllegalArgumentException("医共体主键缺少可解析长度: sdvType="
                    + sdvType + ", biaoshigs=" + biaoshigs);
        }
        long varcharLength = (long) fmt.length() * 3;
        if (varcharLength > MAX_VARCHAR_LENGTH) {
            throw new IllegalArgumentException("医共体主键 VARCHAR 长度超出 Doris 上限: sdvType="
                    + sdvType + ", biaoshigs=" + biaoshigs + ", length=" + varcharLength);
        }
        return varcharCapped(varcharLength);
    }

    private String varcharCapped(long length) {
        if (length > MAX_VARCHAR_LENGTH) {
            log.warn("[MedicalRegistry] VARCHAR 长度 {} 超出 Doris 上限，截断为 {}", length, MAX_VARCHAR_LENGTH);
            length = MAX_VARCHAR_LENGTH;
        }
        if (length <= 0) {
            length = 256;
        }
        return "VARCHAR(" + length + ")";
    }
}
