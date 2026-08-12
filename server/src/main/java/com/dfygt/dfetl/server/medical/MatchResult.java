package com.dfygt.dfetl.server.medical;

/**
 * 数据集匹配结果。
 *
 * @param dataset   匹配到的数据集定义
 * @param matchType 匹配方式（精确/后缀/前缀）
 */
public record MatchResult(
        DatasetDefinition dataset,
        MatchType matchType
) {}
