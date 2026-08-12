package com.dfygt.dfetl.server.service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** 解析必须完整存在且大小写不歧义的字段集合。 */
final class RequiredColumnResolver {

    private RequiredColumnResolver() {
    }

    static List<String> resolveUnique(
            List<String> requestedColumns, List<String> actualColumns, String purpose) {
        List<String> requested = requestedColumns == null ? List.of() : requestedColumns;
        List<String> actual = actualColumns == null ? List.of() : actualColumns;
        Set<String> seen = new HashSet<>();
        List<String> resolved = new ArrayList<>(requested.size());
        for (String requestedColumn : requested) {
            String normalized = requestedColumn == null
                    ? ""
                    : requestedColumn.trim().toLowerCase(Locale.ROOT);
            if (normalized.isEmpty()) {
                throw new IllegalStateException(purpose + "声明了空字段");
            }
            if (!seen.add(normalized)) {
                throw new IllegalStateException(purpose + "重复声明字段: " + requestedColumn);
            }
            List<String> matches = actual.stream()
                    .filter(column -> column != null && column.equalsIgnoreCase(requestedColumn))
                    .toList();
            if (matches.size() != 1) {
                throw new IllegalStateException(purpose + "无法唯一解析源字段: " + requestedColumn
                        + "，匹配数量=" + matches.size());
            }
            resolved.add(matches.getFirst());
        }
        return List.copyOf(resolved);
    }
}
