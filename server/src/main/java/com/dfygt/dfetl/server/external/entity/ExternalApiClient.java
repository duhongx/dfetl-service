package com.dfygt.dfetl.server.external.entity;

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

import java.time.LocalDateTime;

/**
 * 外部 API 调用方身份与授权范围。
 */
@Entity
@Table(name = "external_api_client")
@Getter
@Setter
@NoArgsConstructor
public class ExternalApiClient {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "client_id", nullable = false, unique = true, length = 100)
    private String clientId;

    @Column(name = "client_name", nullable = false, length = 100)
    private String clientName;

    @Column(name = "secret_enc", nullable = false, columnDefinition = "TEXT")
    private String secretEnc;

    @Column(nullable = false)
    private Boolean enabled = true;

    /**
     * 允许访问的医疗机构编码。为空表示不限制；生产环境建议显式配置。
     */
    @Column(name = "allowed_yi_liao_jg_dm", length = 50)
    private String allowedYiLiaoJgDm;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Column(name = "created_at", updatable = false)
    @CreationTimestamp
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    @UpdateTimestamp
    private LocalDateTime updatedAt;
}
