package com.dfygt.dfetl.server.controller;

import com.dfygt.dfetl.server.common.ApiResponse;
import com.dfygt.dfetl.server.dto.DorisTypeMappingRuleDto;
import com.dfygt.dfetl.server.engine.doris.DorisTypeMappingRuleService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/doris-type-mapping-rules")
@RequiredArgsConstructor
public class DorisTypeMappingRuleController {

    private final DorisTypeMappingRuleService ruleService;

    @GetMapping
    public ApiResponse<List<DorisTypeMappingRuleDto>> list() {
        return ApiResponse.ok(ruleService.list());
    }

    @PostMapping
    public ApiResponse<DorisTypeMappingRuleDto> create(@RequestBody DorisTypeMappingRuleDto dto) {
        return ApiResponse.ok(ruleService.create(dto));
    }

    @PostMapping("/init-defaults")
    public ApiResponse<Map<String, Object>> initDefaults() {
        return ApiResponse.ok(ruleService.initDefaults());
    }

    @PutMapping("/{id}")
    public ApiResponse<DorisTypeMappingRuleDto> update(
            @PathVariable Long id,
            @RequestBody DorisTypeMappingRuleDto dto) {
        return ApiResponse.ok(ruleService.update(id, dto));
    }
}
