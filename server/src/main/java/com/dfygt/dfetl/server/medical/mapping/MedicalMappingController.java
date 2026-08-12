package com.dfygt.dfetl.server.medical.mapping;

import com.dfygt.dfetl.server.common.ApiResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 医共体 contract-driven 映射计划 API。
 */
@RestController
@RequestMapping("/api/medical-mapping")
@RequiredArgsConstructor
public class MedicalMappingController {

    private final MedicalMappingService medicalMappingService;

    @PostMapping("/precheck")
    public ApiResponse<MedicalMappingPrecheckResult> precheck(
            @RequestBody @Valid MedicalMappingPrecheckRequest request) {
        return ApiResponse.ok(medicalMappingService.precheck(request));
    }

    @PostMapping("/plan")
    public ApiResponse<MedicalMappingPlanResponse> plan(
            @RequestBody @Valid MedicalMappingBatchPlanRequest request) {
        return ApiResponse.ok(medicalMappingService.plan(request));
    }
}
