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

/** 医共体标准字段在 DFETL 中的当前快照。 */
@Entity
@Table(name = "dfetl_field")
@Getter
@Setter
@NoArgsConstructor
public class DfetlField {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "dataset_id", nullable = false)
    private Long datasetId;

    @Column(name = "medical_field_id", nullable = false, length = 64)
    private String medicalFieldId;

    @Column(name = "field_code", nullable = false, length = 100)
    private String fieldCode;

    @Column(name = "target_field_code", nullable = false, length = 100)
    private String targetFieldCode;

    @Column(name = "field_name", length = 200)
    private String fieldName;

    @Column(name = "field_order")
    private Integer fieldOrder;

    @Column(name = "standard_type", length = 30)
    private String standardType;

    @Column(name = "standard_format", length = 100)
    private String standardFormat;

    @Column(name = "doris_type", nullable = false, length = 100)
    private String dorisType;

    @Column(name = "primary_key", nullable = false)
    private Boolean primaryKey = false;

    @Column(name = "required_by_standard", nullable = false)
    private Boolean requiredByStandard = false;

    @Column(name = "value_domain_code", length = 100)
    private String valueDomainCode;

    @Column(name = "standard_version", length = 50)
    private String standardVersion;

    @Column(name = "field_status", nullable = false, length = 20)
    private String fieldStatus = "ACTIVE";

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
