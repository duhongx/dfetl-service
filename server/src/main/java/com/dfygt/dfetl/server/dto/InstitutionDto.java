package com.dfygt.dfetl.server.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 机构主表 DTO，对应 {@link com.dfygt.dfetl.server.entity.Institution}。
 *
 * <p>用于机构 CRUD API 的请求与响应载荷；字段约束与实体保持一致：
 * <ul>
 *     <li>{@code code} 必填，唯一业务编码（最大 50 位）</li>
 *     <li>{@code name} 必填，机构全称（最大 200 位）</li>
 *     <li>{@code type} ∈ {HOSPITAL, CLINIC, CENTER, COMMUNITY}</li>
 *     <li>{@code level} ∈ {TIER_3, TIER_2, TIER_1}</li>
 *     <li>{@code parentId} 自引用，为空时表示顶级机构</li>
 * </ul>
 */
@Data
public class InstitutionDto {

    private Long id;

    /** 机构业务唯一编码，如 {@code YGT330106H001}。 */
    @NotBlank
    @Size(max = 50)
    private String code;

    /** 机构全称。 */
    @NotBlank
    @Size(max = 200)
    private String name;

    /** 机构简称。 */
    @Size(max = 50)
    private String shortName;

    /** 机构类型：HOSPITAL | CLINIC | CENTER | COMMUNITY。 */
    @Size(max = 20)
    private String type;

    /** 机构等级：TIER_3 | TIER_2 | TIER_1。 */
    @Size(max = 20)
    private String level;

    /** 行政区划代码。 */
    @Size(max = 20)
    private String regionCode;

    /** 上级机构 ID（自引用，可空）。 */
    private Long parentId;

    /** 启用状态；删除采用软删除（设为 false）。默认 true。 */
    private Boolean enabled = true;

    private String description;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
