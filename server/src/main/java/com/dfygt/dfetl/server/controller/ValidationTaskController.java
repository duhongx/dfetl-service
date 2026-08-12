package com.dfygt.dfetl.server.controller;

import com.dfygt.dfetl.server.common.ApiResponse;
import com.dfygt.dfetl.server.dto.ValidationTaskDto;
import com.dfygt.dfetl.server.service.ValidationTaskService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping({"/api/validation", "/api/validations"})
@RequiredArgsConstructor
public class ValidationTaskController {

    private final ValidationTaskService service;

    /** 分页查询 */
    @GetMapping
    public ApiResponse<Page<ValidationTaskDto>> list(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String search) {
        PageRequest pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "id"));
        return ApiResponse.ok(service.findAll(status, search, pageable));
    }

    /** 查单条 */
    @GetMapping("/{id}")
    public ApiResponse<ValidationTaskDto> get(@PathVariable Long id) {
        return ApiResponse.ok(service.findById(id));
    }

    /** 按同步任务查询校验任务，用于 /validation?taskId= 深链打开工作台。 */
    @GetMapping("/by-task/{taskId}")
    public ApiResponse<ValidationTaskDto> getByTaskId(@PathVariable Long taskId) {
        return ApiResponse.ok(service.findByTaskId(taskId));
    }

    /** 校验结果摘要（用于 ValidationPage 结果卡片） */
    @GetMapping("/{id}/result")
    public ApiResponse<Map<String, Object>> result(@PathVariable Long id) {
        ValidationTaskDto dto = service.findById(id);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("id", dto.getId());
        payload.put("taskId", dto.getTaskId());
        payload.put("taskName", dto.getTaskName());
        payload.put("status", dto.getStatus());
        payload.put("method", dto.getMethod());
        payload.put("tables", dto.getTables());
        payload.put("sourceRows", dto.getSourceRows());
        payload.put("targetRows", dto.getTargetRows());
        payload.put("diffRows", dto.getDiffRows());
        payload.put("durationMs", dto.getDurationMs());
        payload.put("lastRunAt", dto.getLastRunAt());
        return ApiResponse.ok(payload);
    }

    /** 创建校验任务 */
    @PostMapping
    public ApiResponse<ValidationTaskDto> create(@RequestBody ValidationTaskDto dto) {
        return ApiResponse.ok(service.create(dto));
    }

    /** 删除 */
    @DeleteMapping("/{id}")
    public ApiResponse<Map<String, String>> delete(@PathVariable Long id) {
        service.delete(id);
        return ApiResponse.ok(Map.of("message", "deleted"));
    }

    /** 为所有尚无校验任务的同步任务补齐创建 */
    @PostMapping("/sync-with-tasks")
    public ApiResponse<Map<String, Integer>> syncWithTasks() {
        int created = service.syncWithSyncTasks();
        return ApiResponse.ok(Map.of("created", created));
    }

    /** 触发校验 */
    @PostMapping("/{id}/run")
    public ApiResponse<ValidationTaskDto> run(@PathVariable Long id) {
        return ApiResponse.ok(service.run(id));
    }

    /** 触发全量校验（走 dispatchTriggered 路径，有悲观锁并发保护） */
    @PostMapping("/trigger-full/{taskId}")
    public ApiResponse<ValidationTaskDto> triggerFull(@PathVariable Long taskId) {
        return ApiResponse.ok(service.triggerFullValidation(taskId));
    }
}
