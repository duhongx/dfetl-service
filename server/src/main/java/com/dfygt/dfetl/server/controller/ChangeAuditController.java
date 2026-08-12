package com.dfygt.dfetl.server.controller;

import com.dfygt.dfetl.server.common.ApiResponse;
import com.dfygt.dfetl.server.service.ChangeAuditService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;

/**
 * Spec 050 — 增量窗口行级变更核查 REST 入口。
 *
 * <p>POST /api/sync-task/{taskId}/change-audit
 * ?windowStart=2024-01-01T00:00:00Z
 * &windowEnd=2024-01-02T00:00:00Z
 */
@RestController
@RequestMapping("/api/sync-task/{taskId}/change-audit")
@RequiredArgsConstructor
public class ChangeAuditController {

    private final ChangeAuditService changeAuditService;

    /**
     * 对指定任务的增量窗口 [windowStart, windowEnd) 执行行级变更核查。
     *
     * @param taskId      同步任务 ID
     * @param windowStart ISO-8601 格式窗口起点（含），必填
     * @param windowEnd   ISO-8601 格式窗口终点（不含），选填
     */
    @PostMapping
    public ApiResponse<ChangeAuditService.AuditReport> run(
            @PathVariable Long taskId,
            @RequestParam String windowStart,
            @RequestParam(required = false) String windowEnd) {
        Instant wsInst = Instant.parse(windowStart);
        Instant weInst = windowEnd != null ? Instant.parse(windowEnd) : null;
        return ApiResponse.ok(changeAuditService.audit(taskId, wsInst, weInst));
    }
}
