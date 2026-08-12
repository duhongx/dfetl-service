package com.dfygt.dfetl.server.controller;

import com.dfygt.dfetl.server.common.ApiResponse;
import com.dfygt.dfetl.server.dto.BatchConfigDiffDto;
import com.dfygt.dfetl.server.dto.BatchMonitorDto;
import com.dfygt.dfetl.server.dto.BatchTaskTemplateDto;
import com.dfygt.dfetl.server.dto.BatchTaskTemplateSourceDto;
import com.dfygt.dfetl.server.service.BatchTaskTemplateService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 批量任务模板 API — 区域医共体场景：统一配置后一键为多个数据源创建同步任务。
 */
@RestController
@RequestMapping("/api/batch-task-template")
@RequiredArgsConstructor
public class BatchTaskTemplateController {

    private final BatchTaskTemplateService templateService;

    // ── CRUD ────────────────────────────────────────────────────────────────────

    @GetMapping
    public ApiResponse<List<BatchTaskTemplateDto>> list() {
        return ApiResponse.ok(templateService.list());
    }

    @GetMapping("/{id}")
    public ApiResponse<BatchTaskTemplateDto> get(@PathVariable Long id) {
        return ApiResponse.ok(templateService.get(id));
    }

    @PostMapping
    public ApiResponse<BatchTaskTemplateDto> create(@RequestBody @Valid BatchTaskTemplateDto dto) {
        return ApiResponse.ok(templateService.create(dto));
    }

    @PutMapping("/{id}")
    public ApiResponse<BatchTaskTemplateDto> update(@PathVariable Long id,
                                                    @RequestBody @Valid BatchTaskTemplateDto dto) {
        return ApiResponse.ok(templateService.update(id, dto));
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable Long id) {
        templateService.delete(id);
        return ApiResponse.ok();
    }

    // ── 数据源管理 ──────────────────────────────────────────────────────────────

    @PostMapping("/{templateId}/source")
    public ApiResponse<BatchTaskTemplateSourceDto> addSource(
            @PathVariable Long templateId,
            @RequestBody @Valid BatchTaskTemplateSourceDto dto) {
        return ApiResponse.ok(templateService.addSource(templateId, dto));
    }

    @DeleteMapping("/{templateId}/source/{sourceId}")
    public ApiResponse<Void> removeSource(@PathVariable Long templateId,
                                          @PathVariable Long sourceId) {
        templateService.removeSource(templateId, sourceId);
        return ApiResponse.ok();
    }

    // ── 预览 ────────────────────────────────────────────────────────────────────

    @GetMapping("/{templateId}/preview")
    public ApiResponse<List<BatchTaskTemplateSourceDto>> preview(@PathVariable Long templateId) {
        return ApiResponse.ok(templateService.preview(templateId));
    }

    // ── 批量创建任务 ────────────────────────────────────────────────────────────

    @PostMapping("/{templateId}/apply")
    public ApiResponse<Map<String, Object>> apply(@PathVariable Long templateId) {
        List<Long> createdIds = templateService.apply(templateId);
        return ApiResponse.ok(Map.of(
                "createdCount", createdIds.size(),
                "createdTaskIds", createdIds
        ));
    }

    // ── 同步配置到已创建的任务 ──────────────────────────────────────────────────

    @PostMapping("/{templateId}/sync-config")
    public ApiResponse<Map<String, Object>> syncConfig(@PathVariable Long templateId) {
        List<Long> updatedIds = templateService.syncConfig(templateId);
        return ApiResponse.ok(Map.of(
                "updatedCount", updatedIds.size(),
                "updatedTaskIds", updatedIds
        ));
    }

    // ── Phase 3: 配置对比 ───────────────────────────────────────────────────────

    /**
     * 对比模板配置与已创建任务的当前配置差异。
     */
    @GetMapping("/{templateId}/config-diff")
    public ApiResponse<BatchConfigDiffDto> configDiff(@PathVariable Long templateId) {
        return ApiResponse.ok(templateService.configDiff(templateId));
    }

    /**
     * 选择性推送配置到指定数据源关联的任务。
     * body: { "sourceIds": [1, 2, 3] }  — 为空则推送所有
     */
    @PostMapping("/{templateId}/sync-config-selective")
    public ApiResponse<Map<String, Object>> syncConfigSelective(
            @PathVariable Long templateId,
            @RequestBody(required = false) Map<String, List<Long>> body) {
        List<Long> sourceIds = (body != null) ? body.get("sourceIds") : null;
        List<Long> updatedIds = templateService.syncConfigSelective(templateId, sourceIds);
        return ApiResponse.ok(Map.of(
                "updatedCount", updatedIds.size(),
                "updatedTaskIds", updatedIds
        ));
    }

    // ── Phase 3: 批量监控面板 ───────────────────────────────────────────────────

    /**
     * 获取单个模板的监控详情。
     */
    @GetMapping("/{templateId}/monitor")
    public ApiResponse<BatchMonitorDto> monitor(@PathVariable Long templateId) {
        return ApiResponse.ok(templateService.monitor(templateId));
    }

    /**
     * 获取所有模板的监控概览。
     */
    @GetMapping("/monitor-all")
    public ApiResponse<List<BatchMonitorDto>> monitorAll() {
        return ApiResponse.ok(templateService.monitorAll());
    }
}
