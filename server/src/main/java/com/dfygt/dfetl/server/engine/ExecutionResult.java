package com.dfygt.dfetl.server.engine;

/**
 * 引擎执行结果
 */
public record ExecutionResult(
        int exitCode,
        long readRows,
        long writeRows,
        long failedRows,
        String errorMsg,
        String executionStatus,
        long engineReadRows,
        long engineWriteRows
) {
    public static final String STATUS_RECONCILE_REQUIRED = "RECONCILE_REQUIRED";
    public static final String STATUS_SUCCESS_WITH_DIRTY_ROWS = "SUCCESS_WITH_DIRTY_ROWS";

    public ExecutionResult(int exitCode, long readRows, long writeRows, long failedRows, String errorMsg) {
        this(exitCode, readRows, writeRows, failedRows, errorMsg, null, readRows, writeRows);
    }

    public ExecutionResult(
            int exitCode,
            long readRows,
            long writeRows,
            long failedRows,
            String errorMsg,
            String executionStatus) {
        this(exitCode, readRows, writeRows, failedRows, errorMsg, executionStatus, readRows, writeRows);
    }

    public static ExecutionResult withEngineMetrics(
            int exitCode,
            long readRows,
            long writeRows,
            long failedRows,
            String errorMsg,
            long engineReadRows,
            long engineWriteRows) {
        return new ExecutionResult(
                exitCode, readRows, writeRows, failedRows, errorMsg, null,
                engineReadRows, engineWriteRows);
    }

    public static ExecutionResult reconcileRequired(
            long readRows,
            long writeRows,
            long failedRows,
            String errorMsg) {
        return new ExecutionResult(
                -1,
                readRows,
                writeRows,
                failedRows,
                errorMsg,
                STATUS_RECONCILE_REQUIRED,
                readRows,
                writeRows);
    }

    public static ExecutionResult reconcileRequired(
            long readRows,
            long writeRows,
            long failedRows,
            String errorMsg,
            long engineReadRows,
            long engineWriteRows) {
        return new ExecutionResult(
                -1,
                readRows,
                writeRows,
                failedRows,
                errorMsg,
                STATUS_RECONCILE_REQUIRED,
                engineReadRows,
                engineWriteRows);
    }

    public boolean requiresReconcile() {
        return STATUS_RECONCILE_REQUIRED.equals(executionStatus);
    }
}
