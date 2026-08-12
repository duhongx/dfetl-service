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

/** 医共体标准数据集在 DFETL 中的只读当前快照。 */
@Entity
@Table(name = "dfetl_dataset")
@Getter
@Setter
@NoArgsConstructor
public class DfetlDataset {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "medical_dataset_id", nullable = false, length = 64)
    private String medicalDatasetId;

    @Column(name = "dataset_code", nullable = false, length = 100)
    private String datasetCode;

    @Column(name = "dataset_name", length = 200)
    private String datasetName;

    @Column(name = "contract_hash", nullable = false, length = 128)
    private String contractHash;

    @Column(name = "dataset_status", nullable = false, length = 20)
    private String datasetStatus = "ACTIVE";

    @Column(name = "last_synced_at", nullable = false)
    private Instant lastSyncedAt;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
