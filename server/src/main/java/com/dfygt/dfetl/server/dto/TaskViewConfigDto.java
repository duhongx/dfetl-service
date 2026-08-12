package com.dfygt.dfetl.server.dto;

import lombok.Data;

/** 创建任务时内联保存的单个源对象字段映射快照。 */
@Data
public class TaskViewConfigDto {
    private String viewName;
    private String fieldMappings;
    private String dorisDdl;
}
