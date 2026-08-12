package com.dfygt.dfetl.server.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.Data;

import java.time.LocalDateTime;

@Data
public class TargetDataSourceDto {

    private Long id;

    @NotBlank
    private String name;

    /** production | staging */
    @NotBlank
    private String environment;

    @NotBlank
    private String feHost;

    @NotNull
    @Positive
    private Integer fePort = 9030;

    private Integer httpPort = 8030;

    private Integer streamLoadPort = 8040;

    @NotBlank
    private String username;

    /** 请求时传明文；响应时返回 **** */
    private String password;

    @NotBlank
    private String database;

    private String defaultWriteDatabase;

    private Integer writeBatchSize = 50000;

    private Integer writeConcurrency = 8;

    private Integer poolSize = 20;

    private Boolean ssl = false;

    private String description;

    private String status;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
