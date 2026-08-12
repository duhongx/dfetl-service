package com.dfygt.dfetl.server.controller;

import com.dfygt.dfetl.server.common.ApiResponse;
import com.dfygt.dfetl.server.service.validation.ColumnTypeCorrectionService;
import com.dfygt.dfetl.server.service.validation.ColumnTypeCorrectionService.CorrectionReport;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 列类型修正 REST API。
 * 触发扫描所有同步任务关联的 Doris 目标表，识别并修正 NUMERIC 无精度映射为 DECIMAL 的列。
 */
@RestController
@RequestMapping("/api/admin/column-type-correction")
@RequiredArgsConstructor
public class ColumnTypeCorrectionController {

    private final ColumnTypeCorrectionService service;

    /**
     * 触发列类型修正扫描。
     *
     * @param dryRun true=仅预览待修正列清单和DDL，不执行修正；false=执行数据验证和ALTER
     */
    @PostMapping
    public ApiResponse<CorrectionReport> correct(
            @RequestParam(defaultValue = "true") boolean dryRun) {
        return ApiResponse.ok(service.scan(dryRun));
    }
}
