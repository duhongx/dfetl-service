package com.dfygt.dfetl.server.controller;

import com.dfygt.dfetl.server.common.ApiResponse;
import com.dfygt.dfetl.server.dto.DfetlPrecheckRunDto;
import com.dfygt.dfetl.server.dto.DfetlPrecheckExportDto;
import com.dfygt.dfetl.server.dto.DfetlPrecheckExportRequest;
import com.dfygt.dfetl.server.dto.DfetlPrecheckIssuePageDto;
import com.dfygt.dfetl.server.dto.DfetlPrecheckIssueQuery;
import com.dfygt.dfetl.server.dto.DfetlPrecheckSummaryDto;
import com.dfygt.dfetl.server.dto.DfetlPrecheckWorkspacePageDto;
import com.dfygt.dfetl.server.medical.precheck.DfetlDataPrecheckService;
import com.dfygt.dfetl.server.medical.precheck.DfetlPrecheckWorkspaceService;
import com.dfygt.dfetl.server.medical.precheck.DfetlPrecheckExportService;
import com.dfygt.dfetl.server.medical.precheck.DfetlPrecheckExportDownloadService;
import com.dfygt.dfetl.server.medical.precheck.DorisPrecheckQueryService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.file.Files;
import java.nio.charset.StandardCharsets;
import java.util.List;

@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class DfetlDataPrecheckController {

    private final DfetlDataPrecheckService service;
    private final DfetlPrecheckWorkspaceService workspaceService;
    private final DorisPrecheckQueryService queryService;
    private final DfetlPrecheckExportService exportService;
    private final DfetlPrecheckExportDownloadService exportDownloadService;

    @GetMapping("/data-prechecks/workspace")
    public ApiResponse<DfetlPrecheckWorkspacePageDto> workspace(
            @RequestParam(required = false) String search,
            @RequestParam(required = false) Long routeId,
            @RequestParam(required = false) Long institutionId,
            @RequestParam(required = false) Long sourceDatasourceId,
            @RequestParam(required = false) String sourceSchema,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.ok(workspaceService.search(
                search, routeId, institutionId, sourceDatasourceId, sourceSchema, status, page, size));
    }

    @PostMapping("/institution-dataset-routes/{routeId}/data-prechecks")
    public ApiResponse<DfetlPrecheckRunDto> run(@PathVariable Long routeId) {
        return ApiResponse.ok(service.runRoute(routeId));
    }

    @GetMapping("/institution-dataset-routes/{routeId}/data-prechecks")
    public ApiResponse<List<DfetlPrecheckRunDto>> history(@PathVariable Long routeId) {
        return ApiResponse.ok(service.listByRoute(routeId));
    }

    @GetMapping("/data-prechecks/{runId}")
    public ApiResponse<DfetlPrecheckRunDto> get(@PathVariable Long runId) {
        return ApiResponse.ok(service.get(runId));
    }

    @GetMapping("/data-prechecks/{runId}/issues")
    public ApiResponse<DfetlPrecheckIssuePageDto> issues(
            @PathVariable Long runId,
            @RequestParam(required = false) String businessPk,
            @RequestParam(required = false) String fieldCode,
            @RequestParam(required = false) String errorType,
            @RequestParam(required = false) String severity,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        return ApiResponse.ok(queryService.issues(runId, new DfetlPrecheckIssueQuery(
                businessPk, fieldCode, errorType, severity, page, size)));
    }

    @PostMapping("/data-prechecks/{runId}/retry")
    public ApiResponse<DfetlPrecheckRunDto> retry(@PathVariable Long runId) {
        return ApiResponse.ok(service.retry(runId));
    }

    @PostMapping("/data-prechecks/{runId}/cancel")
    public ApiResponse<DfetlPrecheckRunDto> cancel(@PathVariable Long runId) {
        return ApiResponse.ok(service.cancel(runId));
    }

    @GetMapping("/data-prechecks/{runId}/summaries")
    public ApiResponse<List<DfetlPrecheckSummaryDto>> summaries(@PathVariable Long runId) {
        return ApiResponse.ok(queryService.summaries(runId));
    }

    @PostMapping("/data-prechecks/{runId}/exports")
    public ApiResponse<DfetlPrecheckExportDto> createExport(
            @PathVariable Long runId,
            @RequestBody(required = false) DfetlPrecheckExportRequest request) {
        return ApiResponse.ok(exportService.create(runId, request));
    }

    @GetMapping("/data-precheck-exports/{exportId}")
    public ApiResponse<DfetlPrecheckExportDto> getExport(@PathVariable Long exportId) {
        return ApiResponse.ok(exportService.get(exportId));
    }

    @GetMapping("/data-precheck-exports/{exportId}/files/{fileIndex}")
    public ResponseEntity<StreamingResponseBody> downloadExportFile(
            @PathVariable Long exportId,
            @PathVariable int fileIndex) {
        DfetlPrecheckExportDownloadService.DownloadFile file =
                exportDownloadService.open(exportId, fileIndex);
        StreamingResponseBody body = output -> Files.copy(file.path(), output);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(file.contentType()))
                .contentLength(file.bytes())
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename(file.name(), StandardCharsets.UTF_8)
                        .build().toString())
                .body(body);
    }

}
