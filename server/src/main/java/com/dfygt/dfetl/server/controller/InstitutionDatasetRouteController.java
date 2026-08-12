package com.dfygt.dfetl.server.controller;

import com.dfygt.dfetl.server.common.ApiResponse;
import com.dfygt.dfetl.server.dto.InstitutionDatasetRouteDto;
import com.dfygt.dfetl.server.service.InstitutionDatasetRouteService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
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
@RequestMapping("/api/institution-dataset-routes")
@RequiredArgsConstructor
public class InstitutionDatasetRouteController {

    private final InstitutionDatasetRouteService service;

    @GetMapping
    public ApiResponse<List<InstitutionDatasetRouteDto>> list(
            @RequestParam(required = false) Long institutionId,
            @RequestParam(required = false) Long datasetId) {
        return ApiResponse.ok(service.list(institutionId, datasetId));
    }

    @GetMapping("/{id}")
    public ApiResponse<InstitutionDatasetRouteDto> get(@PathVariable Long id) {
        return ApiResponse.ok(service.get(id));
    }

    @PostMapping
    public ApiResponse<InstitutionDatasetRouteDto> create(
            @RequestBody @Valid InstitutionDatasetRouteDto request) {
        return ApiResponse.ok(service.create(request));
    }

    @PutMapping("/{id}")
    public ApiResponse<InstitutionDatasetRouteDto> update(
            @PathVariable Long id,
            @RequestBody @Valid InstitutionDatasetRouteDto request) {
        return ApiResponse.ok(service.update(id, request));
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable Long id) {
        service.delete(id);
        return ApiResponse.ok(null);
    }

    @PostMapping("/{id}/validate")
    public ApiResponse<InstitutionDatasetRouteDto> validate(@PathVariable Long id) {
        return ApiResponse.ok(service.validate(id));
    }

    @PostMapping("/{id}/enable")
    public ApiResponse<InstitutionDatasetRouteDto> enable(@PathVariable Long id) {
        return ApiResponse.ok(service.enable(id));
    }

    @PostMapping("/{id}/disable")
    public ApiResponse<InstitutionDatasetRouteDto> disable(@PathVariable Long id) {
        return ApiResponse.ok(service.disable(id));
    }
}
