package com.dfygt.dfetl.server.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;

/**
 * 源端字段类型到 Doris 字段类型的可配置映射规则。
 *
 * <p>内置默认规则覆盖医疗视图标准口径；管理员可以在前端调整规则。
 * 执行期仍以后端规则为准，创建/编辑页只展示同一规则的 preview。</p>
 */
@Entity
@Table(name = "doris_type_mapping_rule")
@Getter
@Setter
@NoArgsConstructor
public class DorisTypeMappingRule {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "profile_name", nullable = false, length = 64)
    private String profileName = "DEFAULT_MEDICAL_VIEW";

    @Column(name = "profile_version", nullable = false)
    private Integer profileVersion = 1;

    @Column(name = "source_dialect", nullable = false, length = 32)
    private String sourceDialect;

    @Column(name = "source_type_pattern", nullable = false, length = 128)
    private String sourceTypePattern;

    @Column(name = "recommended_doris_type", nullable = false, length = 128)
    private String recommendedDorisType;

    @Column(name = "compatibility_level", nullable = false, length = 16)
    private String compatibilityLevel = "PASS";

    @Column(columnDefinition = "TEXT")
    private String reason;

    @Column(nullable = false)
    private Boolean enabled = true;

    @Column(nullable = false)
    private Integer priority = 0;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private Instant updatedAt;
}
