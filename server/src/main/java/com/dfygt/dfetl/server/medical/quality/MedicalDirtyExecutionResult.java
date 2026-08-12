package com.dfygt.dfetl.server.medical.quality;

public record MedicalDirtyExecutionResult(
        boolean applied,
        long excludedRows,
        long warningRows,
        String validSourceQuery
) {

    public static MedicalDirtyExecutionResult empty() {
        return new MedicalDirtyExecutionResult(false, 0, 0, null);
    }

    public boolean hasDirtyRows() {
        return excludedRows > 0 || warningRows > 0;
    }

    public boolean hasExcludedRows() {
        return excludedRows > 0;
    }
}
