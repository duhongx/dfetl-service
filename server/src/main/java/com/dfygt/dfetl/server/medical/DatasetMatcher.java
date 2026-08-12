package com.dfygt.dfetl.server.medical;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * 数据集匹配器：按三级优先级匹配源视图名与规范数据集代码。
 * <ol>
 *   <li>精确匹配：viewName 忽略大小写后等于 dataset.shujujdm()</li>
 *   <li>后缀匹配：viewName 去除 _V 或 _VIEW 后缀后匹配</li>
 *   <li>前缀匹配：viewName 去除已知机构前缀后匹配</li>
 * </ol>
 */
@Component
public class DatasetMatcher {

    private static final Logger log = LoggerFactory.getLogger(DatasetMatcher.class);

    /**
     * 已知机构前缀列表（忽略大小写匹配）。
     * 当前阶段硬编码，后续可从 system_setting 读取 medical.matching.known_prefixes。
     */
    private static final List<String> KNOWN_PREFIXES = List.of("V_", "VW_", "VIEW_");

    /**
     * 后缀模式列表（忽略大小写匹配）。
     */
    private static final List<String> KNOWN_SUFFIXES = List.of("_V", "_VIEW");

    /**
     * 按三级优先级匹配源视图名与规范数据集。
     * <p>首次命中即返回，不继续尝试更低优先级策略。</p>
     *
     * @param viewName 源视图名称
     * @param datasets 规范数据集列表（应为有效数据集，zuofeibz=0）
     * @return 匹配结果，或 Optional.empty()（无匹配）
     */
    public Optional<MatchResult> match(String viewName, List<DatasetDefinition> datasets) {
        if (viewName == null || viewName.isBlank()) {
            return Optional.empty();
        }
        if (datasets == null || datasets.isEmpty()) {
            return Optional.empty();
        }

        // 1. 精确匹配
        List<DatasetDefinition> exactMatches = new ArrayList<>();
        for (DatasetDefinition ds : datasets) {
            if (viewName.equalsIgnoreCase(ds.shujujdm())) {
                exactMatches.add(ds);
            }
        }
        if (!exactMatches.isEmpty()) {
            DatasetDefinition selected = exactMatches.size() == 1
                    ? exactMatches.getFirst()
                    : disambiguate(exactMatches);
            return Optional.of(new MatchResult(selected, MatchType.EXACT));
        }

        // 2. 后缀匹配：去除 _V 或 _VIEW 后缀
        for (String suffix : KNOWN_SUFFIXES) {
            if (viewName.toUpperCase().endsWith(suffix)) {
                String stripped = viewName.substring(0, viewName.length() - suffix.length());
                List<DatasetDefinition> suffixMatches = new ArrayList<>();
                for (DatasetDefinition ds : datasets) {
                    if (stripped.equalsIgnoreCase(ds.shujujdm())) {
                        suffixMatches.add(ds);
                    }
                }
                if (!suffixMatches.isEmpty()) {
                    DatasetDefinition selected = suffixMatches.size() == 1
                            ? suffixMatches.getFirst()
                            : disambiguate(suffixMatches);
                    return Optional.of(new MatchResult(selected, MatchType.SUFFIX));
                }
            }
        }

        // 3. 前缀匹配：去除已知机构前缀
        for (String prefix : KNOWN_PREFIXES) {
            if (viewName.toUpperCase().startsWith(prefix.toUpperCase())) {
                String stripped = viewName.substring(prefix.length());
                List<DatasetDefinition> prefixMatches = new ArrayList<>();
                for (DatasetDefinition ds : datasets) {
                    if (stripped.equalsIgnoreCase(ds.shujujdm())) {
                        prefixMatches.add(ds);
                    }
                }
                if (!prefixMatches.isEmpty()) {
                    DatasetDefinition selected = prefixMatches.size() == 1
                            ? prefixMatches.getFirst()
                            : disambiguate(prefixMatches);
                    return Optional.of(new MatchResult(selected, MatchType.PREFIX));
                }
            }
        }

        return Optional.empty();
    }

    /**
     * 多匹配消歧：在候选列表中按 banben 字段字典序降序取第一个（最高版本）。
     * <p>
     * 注：调用方传入的候选列表已经是有效数据集（zuofeibz=0 已过滤），
     * 因此此处仅按版本号选择最高版本。
     * </p>
     *
     * @param candidates 候选数据集列表（至少 2 个元素）
     * @return 版本号最高的数据集
     */
    DatasetDefinition disambiguate(List<DatasetDefinition> candidates) {
        if (candidates == null || candidates.isEmpty()) {
            throw new IllegalArgumentException("候选列表不能为空");
        }
        if (candidates.size() == 1) {
            return candidates.getFirst();
        }

        log.warn("[MedicalRegistry] 视图匹配到多个候选数据集 (数量={})，按版本号消歧", candidates.size());

        return candidates.stream()
                .max(Comparator.comparing(
                        ds -> ds.banben() != null ? ds.banben() : "",
                        Comparator.naturalOrder()))
                .orElseThrow();
    }
}
