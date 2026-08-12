package com.dfygt.dfetl.server.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.Data;

import java.time.LocalDateTime;

@Data
public class SourceDataSourceDto {

    private Long id;

    @NotBlank
    private String name;

    /** MYSQL | POSTGRESQL | ORACLE | SQLSERVER | DORIS */
    @NotBlank
    private String type;

    @NotBlank
    private String host;

    @NotNull
    @Positive
    private Integer port;

    @NotBlank
    private String database;

    @NotBlank
    private String username;

    /** 请求时传明文；响应时返回 **** */
    private String password;

    private String schema;

    private Boolean readonly = false;

    private Integer queryTimeout = 60;

    private Integer readConcurrency = 4;

    private Integer poolSize = 10;

    private Boolean ssl = false;

    private String description;

    /** 关联机构 ID（df_etl.institution.id），可空 */
    private Long institutionId;

    /**
     * 数据源稳定编码（spec 070）。
     * <p>
     * 格式：{@code {机构首字母}-{库类型}-{序号}}（如 {@code xrmyy-mysql-01}），
     * 由 {@code SourceCodeGenerator} 在创建数据源时自动生成，作为同步任务写入目标表
     * {@code _etl_source_system} 列的稳定血缘标识。
     * <p>
     * 字段语义约束：
     * <ul>
     *   <li>仅由 {@code toDto} 输出（响应方向）；客户端在创建/更新请求中传入的 {@code sourceCode} 一律忽略，
     *       不参与请求绑定/校验，故不加任何 Bean Validation 注解。</li>
     *   <li>系统在创建时自动生成、写入；后续 update 路径不修改本字段（保持稳定血缘）。</li>
     *   <li>前端列表/详情不展示，后端透出仅供调试/排查。</li>
     * </ul>
     */
    private String sourceCode;

    private String status;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
