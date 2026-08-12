package com.dfygt.dfetl.server.service;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

/** 内部向导和外部 API 共同适配到的任务创建意图。 */
@Data
public class TaskCreateIntent {

    @NotNull
    private Long institutionId;

    @NotNull
    private Long datasetId;

    private Long routeId;
    private String name;
}
