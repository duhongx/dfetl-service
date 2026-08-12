package com.dfygt.dfetl.server.controller;

import com.dfygt.dfetl.server.common.ApiResponse;
import com.dfygt.dfetl.server.dto.DfetlDatasetDto;
import com.dfygt.dfetl.server.dto.DfetlDatasetImportResultDto;
import com.dfygt.dfetl.server.dto.DfetlMessagePolicyDto;
import com.dfygt.dfetl.server.dto.DfetlSyncPolicyDto;
import com.dfygt.dfetl.server.dto.DfetlValidationPolicyDto;
import com.dfygt.dfetl.server.service.DfetlDatasetConfigService;
import com.dfygt.dfetl.server.service.DfetlDatasetImportService;
import com.dfygt.dfetl.server.service.DfetlPolicyService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/dfetl-datasets")
@RequiredArgsConstructor
public class DfetlDatasetController {

    private final DfetlDatasetConfigService service;
    private final DfetlDatasetImportService importService;
    private final DfetlPolicyService policyService;

    @GetMapping
    public ApiResponse<List<DfetlDatasetDto>> list(
            @RequestParam(required = false) String search) {
        return ApiResponse.ok(service.list(search));
    }

    @GetMapping("/{id}")
    public ApiResponse<DfetlDatasetDto> get(@PathVariable Long id) {
        return ApiResponse.ok(service.get(id));
    }

    @GetMapping("/{id}/sync-policy")
    public ApiResponse<DfetlSyncPolicyDto> getSyncPolicy(@PathVariable Long id) {
        return ApiResponse.ok(policyService.getSyncPolicy(id));
    }

    @PutMapping("/{id}/sync-policy")
    public ApiResponse<DfetlSyncPolicyDto> updateSyncPolicy(@PathVariable Long id,
                                                            @RequestBody DfetlSyncPolicyDto request) {
        return ApiResponse.ok(policyService.updateSyncPolicy(id, request));
    }

    @GetMapping("/{id}/validation-policy")
    public ApiResponse<DfetlValidationPolicyDto> getValidationPolicy(@PathVariable Long id) {
        return ApiResponse.ok(policyService.getValidationPolicy(id));
    }

    @PutMapping("/{id}/validation-policy")
    public ApiResponse<DfetlValidationPolicyDto> updateValidationPolicy(@PathVariable Long id,
                                                                        @RequestBody DfetlValidationPolicyDto request) {
        return ApiResponse.ok(policyService.updateValidationPolicy(id, request));
    }

    @GetMapping("/{id}/message-policy")
    public ApiResponse<DfetlMessagePolicyDto> getMessagePolicy(@PathVariable Long id) {
        return ApiResponse.ok(policyService.getMessagePolicy(id));
    }

    @PutMapping("/{id}/message-policy")
    public ApiResponse<DfetlMessagePolicyDto> updateMessagePolicy(@PathVariable Long id,
                                                                  @RequestBody DfetlMessagePolicyDto request) {
        return ApiResponse.ok(policyService.updateMessagePolicy(id, request));
    }

    @PostMapping("/sync-medical")
    public ApiResponse<DfetlDatasetImportResultDto> syncMedical() {
        return ApiResponse.ok(importService.synchronizeFromMedicalRegistry());
    }
}
