package com.dfygt.dfetl.server.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Spec 055 / 056：字段级差异接口配置。
 *
 * <p>对应 application.yml 中的 {@code dfetl.diff.*}。
 *
 * @param maxColumnsPerRow      单次返回的最大列数（防御 200+ 列大宽表）
 * @param maxValueLength        单字段展示值最大长度（超过截断）
 * @param staleThresholdSeconds 实时查询时间与 diff 落库时间的差超过此值时，前端提示 possiblyChanged
 * @param maskColumnPatterns    全局敏感列名正则；命中的列在返回前会被脱敏
 * @param precomputeEnabled     spec 056：是否在 Checksum 完成后异步预计算字段差异
 * @param precomputeTopN        spec 056：每次预计算的 diff 行数上限（按 id ASC）
 * @param precomputeParallelism spec 056：全局并发许可（Semaphore 容量）
 * @param precomputeIncludeEqual spec 056：预计算是否包含 EQUAL 列；默认 false 节省存储
 */
@ConfigurationProperties(prefix = "dfetl.diff")
public record DiffProperties(
        Integer maxColumnsPerRow,
        Integer maxValueLength,
        Integer staleThresholdSeconds,
        List<String> maskColumnPatterns,
        Boolean precomputeEnabled,
        Integer precomputeTopN,
        Integer precomputeParallelism,
        Boolean precomputeIncludeEqual
) {
    public DiffProperties {
        if (maxColumnsPerRow == null || maxColumnsPerRow <= 0) maxColumnsPerRow = 200;
        if (maxValueLength == null || maxValueLength <= 0) maxValueLength = 256;
        if (staleThresholdSeconds == null || staleThresholdSeconds <= 0) staleThresholdSeconds = 60;
        if (maskColumnPatterns == null) {
            maskColumnPatterns = List.of(
                    "(?i).*(password|pwd|secret|token).*",
                    "(?i).*(phone|mobile|idcard|id_card|email).*"
            );
        }
        if (precomputeEnabled == null) precomputeEnabled = true;
        if (precomputeTopN == null || precomputeTopN <= 0) precomputeTopN = 50;
        if (precomputeParallelism == null || precomputeParallelism <= 0) precomputeParallelism = 2;
        if (precomputeIncludeEqual == null) precomputeIncludeEqual = false;
    }

    /** 预编译的脱敏正则集合，启动期一次性构建，避免每行重复编译。 */
    public List<Pattern> compiledMaskPatterns() {
        return maskColumnPatterns.stream().map(Pattern::compile).collect(Collectors.toList());
    }
}
