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
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.Locale;

/** 数据预检问题的异步导出事实；筛选条件和请求人创建后不可变。 */
@Entity
@Table(name = "dfetl_precheck_export")
@Getter
@Setter
@NoArgsConstructor
public class DfetlPrecheckExport {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "run_id", nullable = false)
    private Long runId;
    @Column(name = "request_key", nullable = false, length = 128, updatable = false)
    private String requestKey;
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "filter_snapshot", nullable = false, columnDefinition = "JSONB", updatable = false)
    private String filterSnapshot = "{}";
    @Column(name = "export_format", nullable = false, length = 10, updatable = false)
    private String exportFormat;
    @Column(nullable = false, length = 20)
    private String status = "PENDING";
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "file_manifest", nullable = false, columnDefinition = "JSONB")
    private String fileManifest = "[]";
    @Column(name = "row_count", nullable = false)
    private Long rowCount = 0L;
    @Column(name = "byte_count", nullable = false)
    private Long byteCount = 0L;
    @Column(name = "requested_by", nullable = false, length = 100, updatable = false)
    private String requestedBy;
    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;
    @Column(name = "started_at")
    private Instant startedAt;
    @Column(name = "finished_at")
    private Instant finishedAt;
    @Column(name = "expires_at")
    private Instant expiresAt;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public static DfetlPrecheckExport pending(
            Long runId,
            String requestKey,
            String filterSnapshot,
            String exportFormat,
            String requestedBy) {
        DfetlPrecheckExport export = new DfetlPrecheckExport();
        export.runId = requiredPositive(runId, "runId");
        export.requestKey = required(requestKey, "requestKey");
        export.filterSnapshot = required(filterSnapshot, "filterSnapshot");
        export.exportFormat = required(exportFormat, "exportFormat").toUpperCase(Locale.ROOT);
        export.requestedBy = required(requestedBy, "requestedBy");
        return export;
    }

    private static Long requiredPositive(Long value, String label) {
        if (value == null || value <= 0) {
            throw new IllegalArgumentException(label + " 必须为正整数");
        }
        return value;
    }

    private static String required(String value, String label) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(label + " 不能为空");
        }
        return value.trim();
    }
}
