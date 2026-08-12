package com.dfygt.dfetl.server.controller;

import com.dfygt.dfetl.server.common.ApiResponse;
import com.dfygt.dfetl.server.dto.ConnectionTestResult;
import com.dfygt.dfetl.server.dto.CustomSqlRequest;
import com.dfygt.dfetl.server.dto.SourceDataSourceDto;
import com.dfygt.dfetl.server.service.SourceDataSourceService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/datasource/source")
@RequiredArgsConstructor
public class SourceDataSourceController {

    private final SourceDataSourceService service;

    @GetMapping
    public ApiResponse<List<SourceDataSourceDto>> list(@RequestParam(required = false) Long institutionId) {
        if (institutionId != null) {
            return ApiResponse.ok(service.findByInstitutionId(institutionId));
        }
        return ApiResponse.ok(service.findAll());
    }

    @GetMapping("/{id}")
    public ApiResponse<SourceDataSourceDto> get(@PathVariable Long id) {
        return ApiResponse.ok(service.findById(id));
    }

    @PostMapping
    public ApiResponse<SourceDataSourceDto> create(@RequestBody @Valid SourceDataSourceDto dto) {
        return ApiResponse.ok(service.create(dto));
    }

    @PutMapping("/{id}")
    public ApiResponse<SourceDataSourceDto> update(
            @PathVariable Long id,
            @RequestBody @Valid SourceDataSourceDto dto) {
        return ApiResponse.ok(service.update(id, dto));
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable Long id) {
        service.delete(id);
        return ApiResponse.ok();
    }

    @PostMapping("/{id}/test")
    public ApiResponse<ConnectionTestResult> test(@PathVariable Long id) {
        return ApiResponse.ok(service.testConnection(id));
    }

    /** 不依赖已存数据源的"试连接"——用于新建表单尚未保存时即时验证。 */
    @PostMapping("/test-config")
    public ApiResponse<ConnectionTestResult> testConfig(@RequestBody SourceDataSourceDto dto) {
        return ApiResponse.ok(service.testConnectionByDto(dto));
    }

    /** 启用/禁用切换 —— 仅更新 status 字段，避免前端回传完整 DTO。 */
    @PatchMapping("/{id}/status")
    public ApiResponse<SourceDataSourceDto> updateStatus(
            @PathVariable Long id,
            @RequestParam String status) {
        return ApiResponse.ok(service.updateStatus(id, status));
    }

    @GetMapping("/{id}/schemas")
    public ApiResponse<List<String>> schemas(@PathVariable Long id) {
        return ApiResponse.ok(service.listSchemas(id));
    }

    @GetMapping("/{id}/tables")
    public ApiResponse<List<SourceDataSourceService.TableInfo>> tables(
            @PathVariable Long id,
            @RequestParam(defaultValue = "") String schema) {
        return ApiResponse.ok(service.listTables(id, schema));
    }

    @GetMapping("/{id}/columns")
    public ApiResponse<List<SourceDataSourceService.ColumnInfo>> columns(
            @PathVariable Long id,
            @RequestParam(defaultValue = "") String schema,
            @RequestParam String table) {
        return ApiResponse.ok(service.listColumns(id, schema, table));
    }

    @GetMapping("/{id}/rowcount")
    public ApiResponse<Long> rowcount(
            @PathVariable Long id,
            @RequestParam(defaultValue = "") String schema,
            @RequestParam String table) {
        return ApiResponse.ok(service.getTableRowCount(id, schema, table));
    }

    /** spec 040：视图档位评估（A/B/C/D） */
    @GetMapping("/{id}/view-acceptance")
    public ApiResponse<SourceDataSourceService.ViewAcceptance> viewAcceptance(
            @PathVariable Long id,
            @RequestParam(defaultValue = "") String schema,
            @RequestParam String table) {
        return ApiResponse.ok(service.evaluateViewAcceptance(id, schema, table));
    }

    /** Spec 052：源表样本数据预览（前 N 行），用于 WizardModal Step 4。 */
    @GetMapping("/{id}/preview")
    public ApiResponse<java.util.List<java.util.LinkedHashMap<String, String>>> preview(
            @PathVariable Long id,
            @RequestParam String schema,
            @RequestParam String table,
            @RequestParam(defaultValue = "10") int limit) {
        return ApiResponse.ok(service.previewData(id, schema, table, limit));
    }

    @PostMapping("/{id}/custom-sql/validate")
    public ApiResponse<String> validateCustomSql(
            @PathVariable Long id,
            @RequestBody CustomSqlRequest request) {
        return ApiResponse.ok(service.validateCustomSql(id, request.sql()));
    }

    @PostMapping("/{id}/custom-sql/columns")
    public ApiResponse<List<SourceDataSourceService.ColumnInfo>> customSqlColumns(
            @PathVariable Long id,
            @RequestBody CustomSqlRequest request) {
        return ApiResponse.ok(service.listCustomSqlColumns(id, request.sql()));
    }

    @PostMapping("/{id}/custom-sql/preview")
    public ApiResponse<java.util.List<java.util.LinkedHashMap<String, String>>> customSqlPreview(
            @PathVariable Long id,
            @RequestBody CustomSqlRequest request,
            @RequestParam(defaultValue = "10") int limit) {
        return ApiResponse.ok(service.previewCustomSql(id, request.sql(), limit));
    }
}
