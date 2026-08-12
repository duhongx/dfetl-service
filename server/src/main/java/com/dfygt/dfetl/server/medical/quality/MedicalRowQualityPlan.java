package com.dfygt.dfetl.server.medical.quality;

import java.util.List;

public record MedicalRowQualityPlan(
        String blockingRowsQuery,
        String warningRowsQuery,
        String validSourceQuery,
        List<String> blockingErrorTypes,
        List<String> warningErrorTypes
) {

    public boolean hasRowBlockingChecks() {
        return blockingErrorTypes != null && !blockingErrorTypes.isEmpty();
    }

    public boolean hasWarningChecks() {
        return warningErrorTypes != null && !warningErrorTypes.isEmpty();
    }
}
