package com.dfygt.dfetl.server.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

/**
 * 机构主表 — 医共体场景下的医疗机构一等公民。
 *
 * <p>对应迁移脚本 {@code db/migration_institution.sql} 中的 {@code df_etl.institution}：
 * 通过 {@code parent_id} 自引用支持医共体层级；删除采用软删除（{@code enabled = false}），
 * 物理删除受关联保护（参见 {@code InstitutionService.delete}）。
 */
@Entity
@Table(name = "institution", schema = "df_etl")
@Getter
@Setter
@NoArgsConstructor
public class Institution {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 机构业务唯一编码，如 {@code YGT330106H001}。 */
    @Column(nullable = false, unique = true, length = 50)
    private String code;

    /** 机构全称。 */
    @Column(nullable = false, length = 200)
    private String name;

    /** 机构简称。 */
    @Column(name = "short_name", length = 50)
    private String shortName;

    /** 机构类型：HOSPITAL | CLINIC | CENTER | COMMUNITY。 */
    @Column(length = 20)
    private String type;

    /** 机构等级：TIER_3 | TIER_2 | TIER_1。 */
    @Column(length = 20)
    private String level;

    /** 行政区划代码。 */
    @Column(name = "region_code", length = 20)
    private String regionCode;

    /** 上级机构 ID（自引用，可空）。 */
    @Column(name = "parent_id")
    private Long parentId;

    /** 启用状态；删除采用软删除（设为 false）。 */
    @Column(nullable = false)
    private Boolean enabled = true;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Column(name = "created_at", updatable = false)
    @CreationTimestamp
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    @UpdateTimestamp
    private LocalDateTime updatedAt;
}
