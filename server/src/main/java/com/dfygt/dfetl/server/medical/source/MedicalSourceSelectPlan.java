package com.dfygt.dfetl.server.medical.source;

import java.util.List;

/**
 * 医共体 contract-driven Reader SELECT 计划。
 */
public record MedicalSourceSelectPlan(
        String sql,
        List<String> selectedColumns,
        List<String> blockers,
        List<String> warnings,
        List<String> ignoredSourceFields
) {
    public MedicalSourceSelectPlan(
            String sql,
            List<String> selectedColumns,
            List<String> blockers,
            List<String> ignoredSourceFields) {
        this(sql, selectedColumns, blockers, List.of(), ignoredSourceFields);
    }

    public boolean hasBlockers() {
        return blockers != null && !blockers.isEmpty();
    }
}
