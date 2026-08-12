package com.dfygt.dfetl.server.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 任务日志查询响应 DTO
 * 用于 GET /api/logs/task/{taskId} 接口返回
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
public class TaskLogResponse {

    /** 过滤后的日志行列表 */
    private List<String> lines;

    /** 返回行数 */
    private int totalLines;

    /** Job_Config JSON 内容（可为 null） */
    private String jobConfig;

    /** Job_Config 文件路径（可为 null） */
    private String jobConfigFile;
}
