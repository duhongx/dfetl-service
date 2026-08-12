package com.dfygt.dfetl.server.external.controller;

import com.dfygt.dfetl.server.common.ApiResponse;
import com.dfygt.dfetl.server.external.dto.ExternalDatasetTaskIdentityRequest;
import com.dfygt.dfetl.server.external.dto.ExternalDatasetTaskOperationResponse;
import com.dfygt.dfetl.server.external.dto.ExternalMessagePublishStatusResponse;
import com.dfygt.dfetl.server.external.dto.ExternalSyncTaskListResponse;
import com.dfygt.dfetl.server.external.dto.ExternalSyncTaskRequest;
import com.dfygt.dfetl.server.external.security.ExternalApiAuthorizationService;
import com.dfygt.dfetl.server.external.service.ExternalDatasetTaskService;
import com.dfygt.dfetl.server.external.service.ExternalMessagePublishRetryService;
import com.dfygt.dfetl.server.external.service.ExternalMessagePublishStatusService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 面向医共体业务方、以机构和标准数据集为唯一业务输入的任务 API。 */
@RestController
@RequiredArgsConstructor
public class ExternalSyncTaskController {

    private final ExternalDatasetTaskService service;
    private final ExternalApiAuthorizationService authorizationService;
    private final ExternalMessagePublishStatusService messagePublishStatusService;
    private final ExternalMessagePublishRetryService messagePublishRetryService;

    @PostMapping("/api/v1/sync-task-plans")
    public ApiResponse<ExternalSyncTaskListResponse> plan(
            @RequestBody @Valid ExternalSyncTaskRequest request) {
        authorizationService.assertAllowed(request.getYiLiaoJgDm());
        return ApiResponse.ok(service.plan(request));
    }

    @PostMapping("/api/v1/sync-tasks")
    public ApiResponse<ExternalSyncTaskListResponse> create(
            @RequestBody @Valid ExternalSyncTaskRequest request) {
        authorizationService.assertAllowed(request.getYiLiaoJgDm());
        return ApiResponse.ok(service.create(request));
    }

    @GetMapping("/api/v1/sync-tasks")
    public ApiResponse<ExternalDatasetTaskOperationResponse> lookup(
            @RequestParam String yiLiaoJgDm,
            @RequestParam String datasetCode) {
        authorizationService.assertAllowed(yiLiaoJgDm);
        return ApiResponse.ok(service.lookup(new ExternalDatasetTaskIdentityRequest(yiLiaoJgDm, datasetCode)));
    }

    @DeleteMapping("/api/v1/sync-tasks")
    public ApiResponse<ExternalDatasetTaskOperationResponse> delete(
            @RequestBody @Valid ExternalDatasetTaskIdentityRequest request) {
        authorizationService.assertAllowed(request.yiLiaoJgDm());
        return ApiResponse.ok(service.delete(request));
    }

    @PostMapping("/api/v1/sync-runs")
    public ApiResponse<ExternalDatasetTaskOperationResponse> run(
            @RequestBody @Valid ExternalDatasetTaskIdentityRequest request) {
        authorizationService.assertAllowed(request.yiLiaoJgDm());
        return ApiResponse.ok(service.run(request));
    }

    @GetMapping("/api/v1/message-publish-runs/{executionId}")
    public ApiResponse<ExternalMessagePublishStatusResponse> messagePublishStatus(
            @PathVariable Long executionId) {
        return ApiResponse.ok(messagePublishStatusService.get(executionId));
    }

    @PostMapping("/api/v1/message-publish-runs/{executionId}/retries")
    public ApiResponse<ExternalMessagePublishStatusResponse> retryMessagePublish(
            @PathVariable Long executionId) {
        return ApiResponse.ok(messagePublishRetryService.retry(executionId));
    }
}
