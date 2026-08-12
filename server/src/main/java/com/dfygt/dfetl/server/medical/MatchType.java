package com.dfygt.dfetl.server.medical;

/**
 * 数据集匹配类型枚举。
 * <ul>
 *   <li>EXACT — 精确匹配（忽略大小写后等于 shujujdm）</li>
 *   <li>SUFFIX — 后缀匹配（去除 _V/_VIEW 后缀后匹配）</li>
 *   <li>PREFIX — 前缀匹配（去除已知机构前缀后匹配）</li>
 * </ul>
 */
public enum MatchType {
    EXACT,
    SUFFIX,
    PREFIX
}
