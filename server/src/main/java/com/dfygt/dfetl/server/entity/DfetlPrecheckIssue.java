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

import java.time.Instant;

/** 数据预检发现的一条字段级问题。 */
@Entity
@Table(name = "dfetl_precheck_issue")
@Getter
@Setter
@NoArgsConstructor
public class DfetlPrecheckIssue {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "run_id", nullable = false)
    private Long runId;
    @Column(name = "issue_key", nullable = false, length = 128)
    private String issueKey;
    @Column(name = "source_row_hash", nullable = false, length = 64)
    private String sourceRowHash;
    @Column(name = "business_pk_json", columnDefinition = "TEXT")
    private String businessPkJson;
    @Column(name = "raw_row_json", nullable = false, columnDefinition = "TEXT")
    private String rawRowJson;
    @Column(name = "field_code", length = 100)
    private String fieldCode;
    @Column(name = "field_name", length = 200)
    private String fieldName;
    @Column(name = "source_column", length = 100)
    private String sourceColumn;
    @Column(name = "target_column", length = 100)
    private String targetColumn;
    @Column(name = "error_type", nullable = false, length = 50)
    private String errorType;
    @Column(name = "standard_rule", length = 500)
    private String standardRule;
    @Column(name = "raw_value", columnDefinition = "TEXT")
    private String rawValue;
    @Column(name = "normalized_value", columnDefinition = "TEXT")
    private String normalizedValue;
    @Column(name = "error_message", nullable = false, columnDefinition = "TEXT")
    private String errorMessage;
    @Column(nullable = false, length = 20)
    private String severity;
    @Column(name = "remediation_status", nullable = false, length = 20)
    private String remediationStatus = "NEW";

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
}
