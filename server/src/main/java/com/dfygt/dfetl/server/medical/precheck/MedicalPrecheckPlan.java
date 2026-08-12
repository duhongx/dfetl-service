package com.dfygt.dfetl.server.medical.precheck;

import java.util.List;

/**
 * 医共体预检计划。
 */
public record MedicalPrecheckPlan(
        List<MedicalPrecheckFinding> findings,
        List<MedicalPrecheckCheck> checks
) {
    public boolean hasBlockers() {
        return findings != null && findings.stream()
                .anyMatch(finding -> finding.severity() == MedicalPrecheckSeverity.BLOCKER);
    }
}
