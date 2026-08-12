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

/** 机构标准数据集到现场源对象和目标表的显式路由。 */
@Entity
@Table(name = "institution_dataset_route")
@Getter
@Setter
@NoArgsConstructor
public class InstitutionDatasetRoute {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "institution_id", nullable = false)
    private Long institutionId;

    @Column(name = "dataset_id", nullable = false)
    private Long datasetId;

    @Column(name = "source_datasource_id", nullable = false)
    private Long sourceDatasourceId;

    @Column(name = "source_schema", nullable = false, length = 100)
    private String sourceSchema;

    @Column(name = "source_object", nullable = false, length = 200)
    private String sourceObject;

    @Column(name = "source_object_type", nullable = false, length = 30)
    private String sourceObjectType = "VIEW";

    @Column(name = "target_datasource_id", nullable = false)
    private Long targetDatasourceId;

    @Column(name = "target_table", nullable = false, length = 200)
    private String targetTable;

    @Column(nullable = false)
    private Boolean enabled = false;

    @Column(name = "validation_status", nullable = false, length = 20)
    private String validationStatus = "PENDING";

    @Column(name = "validation_summary", columnDefinition = "TEXT")
    private String validationSummary;

    @Column(name = "validation_details_json", columnDefinition = "TEXT")
    private String validationDetailsJson;

    @Column(name = "last_validated_at")
    private Instant lastValidatedAt;

    @Column(name = "validated_contract_hash", length = 128)
    private String validatedContractHash;

    @Column(name = "validated_route_revision")
    private Long validatedRouteRevision;

    @Column(name = "route_revision", nullable = false)
    private Long routeRevision = 1L;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
