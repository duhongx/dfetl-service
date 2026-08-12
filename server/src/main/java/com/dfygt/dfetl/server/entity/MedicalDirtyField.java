package com.dfygt.dfetl.server.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * 医共体字段级问题明细。
 */
@Entity
@Table(name = "medical_dirty_field")
@Getter
@Setter
@NoArgsConstructor
public class MedicalDirtyField {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "dirty_row_id", nullable = false)
    private MedicalDirtyRow dirtyRow;

    @Column(name = "field_code", nullable = false, length = 100)
    private String fieldCode;

    @Column(name = "field_name", length = 200)
    private String fieldName;

    @Column(name = "source_column", length = 200)
    private String sourceColumn;

    @Column(name = "target_column", length = 200)
    private String targetColumn;

    @Column(name = "error_type", nullable = false, length = 80)
    private String errorType;

    @Column(name = "standard_rule", length = 200)
    private String standardRule;

    @Column(name = "value_domain_code", length = 100)
    private String valueDomainCode;

    @Column(name = "value_domain_mode", length = 30)
    private String valueDomainMode;

    @Column(name = "value_domain_allowed_count")
    private Integer valueDomainAllowedCount;

    @Column(name = "raw_value", columnDefinition = "TEXT")
    private String rawValue;

    @Column(name = "normalized_value", columnDefinition = "TEXT")
    private String normalizedValue;

    @Column(columnDefinition = "TEXT")
    private String message;

    @Column(nullable = false, length = 50)
    private String severity;
}
