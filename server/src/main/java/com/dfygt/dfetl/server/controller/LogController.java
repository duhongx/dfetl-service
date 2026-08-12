package com.dfygt.dfetl.server.controller;

import com.dfygt.dfetl.server.common.ApiResponse;
import com.dfygt.dfetl.server.dto.TaskLogResponse;
import com.dfygt.dfetl.server.service.LogService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/logs")
@RequiredArgsConstructor
public class LogController {

    private final LogService logService;

    /**
     * 查询任务日志
     * GET /api/logs/task/{taskId}?execId=xxx
     */
    @GetMapping("/task/{taskId}")
    public ApiResponse<TaskLogResponse> getTaskLogs(
            @PathVariable Long taskId,
            @RequestParam(required = false) Long execId) {
        if (taskId <= 0) {
            throw new IllegalArgumentException("taskId 必须为正整数");
        }
        return ApiResponse.ok(logService.getTaskLogs(taskId, execId));
    }

    /**
     * 下载任务日志
     * GET /api/logs/task/{taskId}/download?execId=xxx
     */
    @GetMapping("/task/{taskId}/download")
    public ResponseEntity<byte[]> downloadTaskLogs(
            @PathVariable Long taskId,
            @RequestParam(required = false) Long execId) {
        if (taskId <= 0) {
            throw new IllegalArgumentException("taskId 必须为正整数");
        }
        return logService.buildDownloadResponse(taskId, execId);
    }
}
