package com.dfygt.dfetl.server.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;
import java.util.Map;
import java.util.Set;

/** 一次路由全量数据预检；任务与窗口字段仅用于兼容查询历史记录。 */
@Entity
@Table(name = "dfetl_precheck_run")
@Getter
@Setter
@NoArgsConstructor
public class DfetlPrecheckRun {

    private static final Set<String> ACTIVE_STATUSES =
            Set.of("PENDING", "LOADING", "VALIDATING");
    private static final Set<String> TERMINAL_STATUSES =
            Set.of("HAS_ERRORS", "PASSED", "FAILED", "CANCELLED");
    private static final Map<String, Set<String>> ALLOWED_TRANSITIONS = Map.of(
            "PENDING", Set.of("LOADING", "FAILED", "CANCELLED"),
            "LOADING", Set.of("VALIDATING", "FAILED", "CANCELLED"),
            "VALIDATING", Set.of("HAS_ERRORS", "PASSED", "FAILED", "CANCELLED"));

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "route_id", nullable = false)
    private Long routeId;
    @Column(name = "dataset_id", nullable = false)
    private Long datasetId;
    @Column(name = "institution_id", nullable = false)
    private Long institutionId;
    @Column(name = "task_id")
    private Long taskId;
    @Column(name = "execution_id")
    private Long executionId;
    @Column(name = "retry_of_run_id")
    private Long retryOfRunId;

    @Column(name = "run_type", nullable = false, length = 30)
    private String runType;
    @Column(name = "scope_type", nullable = false, length = 30)
    private String scopeType;
    @Column(name = "window_start")
    private Instant windowStart;
    @Column(name = "window_end")
    private Instant windowEnd;
    @Column(name = "window_start_id")
    private Long windowStartId;
    @Column(name = "window_end_id")
    private Long windowEndId;

    @Column(name = "contract_hash", nullable = false, length = 128)
    private String contractHash;
    @Column(name = "route_revision", nullable = false)
    private Long routeRevision;
    @Column(name = "target_schema_hash", length = 128)
    private String targetSchemaHash;
    @Column(nullable = false, length = 20)
    private String status = "PENDING";
    @Column(nullable = false, length = 20)
    private String stage = "PREPARING";
    @Column(name = "progress_percent", nullable = false)
    private Short progressPercent = 0;
    @Column(name = "engine_job_id", length = 128)
    private String engineJobId;
    @Column(name = "staging_table", length = 200)
    private String stagingTable;
    @Column(name = "source_rows", nullable = false)
    private Long sourceRows = 0L;
    @Column(name = "loaded_rows", nullable = false)
    private Long loadedRows = 0L;
    @Column(name = "checked_rows", nullable = false)
    private Long checkedRows = 0L;
    @Column(name = "issue_count", nullable = false)
    private Long issueCount = 0L;
    @Column(name = "scanned_rows", nullable = false)
    private Long scannedRows = 0L;
    @Column(name = "passed_rows", nullable = false)
    private Long passedRows = 0L;
    @Column(name = "blocker_rows", nullable = false)
    private Long blockerRows = 0L;
    @Column(name = "warning_rows", nullable = false)
    private Long warningRows = 0L;
    @Column(name = "fixed_issue_rows", nullable = false)
    private Long fixedIssueRows = 0L;
    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;
    @Column(name = "started_at")
    private Instant startedAt;
    @Column(name = "finished_at")
    private Instant finishedAt;
    @Column(name = "raw_cleaned_at")
    private Instant rawCleanedAt;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public boolean isActive() {
        return ACTIVE_STATUSES.contains(status);
    }

    public boolean isTerminal() {
        return TERMINAL_STATUSES.contains(status);
    }

    public void transitionTo(String nextStatus, String nextStage, short nextProgress) {
        if (nextStatus == null || nextStage == null || nextProgress < 0 || nextProgress > 100) {
            throw new IllegalArgumentException("预检运行目标状态、阶段和进度必须有效");
        }
        String current = status == null ? "" : status;
        if (!ALLOWED_TRANSITIONS.getOrDefault(current, Set.of()).contains(nextStatus)) {
            throw new IllegalStateException(
                    "不允许的数据预检状态转换: " + current + " -> " + nextStatus);
        }
        status = nextStatus;
        stage = nextStage;
        progressPercent = nextProgress;
    }
}
