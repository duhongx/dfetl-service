package com.dfygt.dfetl.server.external.controller;

import com.dfygt.dfetl.server.common.ApiResponse;
import com.dfygt.dfetl.server.external.dto.ExternalApiClientCreateRequest;
import com.dfygt.dfetl.server.external.dto.ExternalApiClientDto;
import com.dfygt.dfetl.server.external.dto.ExternalApiClientSecretResponse;
import com.dfygt.dfetl.server.external.dto.ExternalApiClientUpdateRequest;
import com.dfygt.dfetl.server.external.service.ExternalApiClientAdminService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 系统设置：外部 API HMAC client 管理。
 */
@RestController
@RequestMapping("/api/settings/external-api/clients")
@RequiredArgsConstructor
public class ExternalApiClientAdminController {

    private final ExternalApiClientAdminService service;

    @GetMapping
    public ApiResponse<List<ExternalApiClientDto>> list() {
        return ApiResponse.ok(service.list());
    }

    @PostMapping
    public ApiResponse<ExternalApiClientSecretResponse> create(
            @RequestBody @Valid ExternalApiClientCreateRequest request) {
        return ApiResponse.ok(service.create(request));
    }

    @PutMapping("/{id}")
    public ApiResponse<ExternalApiClientDto> update(
            @PathVariable Long id,
            @RequestBody @Valid ExternalApiClientUpdateRequest request) {
        return ApiResponse.ok(service.update(id, request));
    }

    @PostMapping("/{id}/reset-secret")
    public ApiResponse<ExternalApiClientSecretResponse> resetSecret(@PathVariable Long id) {
        return ApiResponse.ok(service.resetSecret(id));
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable Long id) {
        service.delete(id);
        return ApiResponse.ok();
    }
}
