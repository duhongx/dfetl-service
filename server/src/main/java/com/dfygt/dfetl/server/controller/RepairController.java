package com.dfygt.dfetl.server.controller;

import com.dfygt.dfetl.server.common.ApiResponse;
import com.dfygt.dfetl.server.entity.ValidationRun;
import com.dfygt.dfetl.server.repository.ValidationRunRepository;
import com.dfygt.dfetl.server.service.RepairService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Spec 024：差异修复 REST 入口。
 */
@RestController
@RequestMapping("/api/sync-task/{taskId}/repair")
@RequiredArgsConstructor
public class RepairController {

    private final RepairService repairService;
    private final ValidationRunRepository validationRunRepository;

    /** 兼容入口：优先按 runId 解析，未命中时回退到 legacy execId。 */
    @PostMapping("/{validationId}")
    public ApiResponse<RepairService.RepairReport> repair(@PathVariable Long taskId,
                                                          @PathVariable Long validationId,
                                                          @RequestParam(defaultValue = "false") boolean forceDelete,
                                                          @RequestParam(defaultValue = "false") boolean dryRun,
                                                          @RequestParam(required = false) Integer maxRows) {
        Long legacyExecId = resolveLegacyExecId(taskId, validationId);
        // spec validation-workbench-redesign · Task P1-6.2：用户主动入口 → MANUAL
        return ApiResponse.ok(repairService.repair(taskId, legacyExecId, forceDelete, dryRun, maxRows, "MANUAL"));
    }

    /** 查询某次校验下各 repair_status 的计数。 */
    @GetMapping("/{validationId}/summary")
    public ApiResponse<Map<String, Long>> summary(@PathVariable Long taskId,
                                                  @PathVariable Long validationId) {
        Long legacyExecId = resolveLegacyExecId(taskId, validationId);
        return ApiResponse.ok(repairService.summary(taskId, legacyExecId));
    }

    private Long resolveLegacyExecId(Long taskId, Long validationId) {
        return validationRunRepository.findByIdAndTaskId(validationId, taskId)
                .map(ValidationRun::getLegacyExecId)
                .orElse(validationId);
    }
}
