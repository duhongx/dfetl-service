package com.dfygt.dfetl.server.controller;

import com.dfygt.dfetl.server.common.ApiResponse;
import com.dfygt.dfetl.server.medical.*;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 医疗规范注册表 REST API。
 * <p>
 * 提供规范数据集浏览、DDL 预览、配置预览和批量建表功能。
 * 优先使用全局配置（MedicalRegistryConfig），registryDsId 参数为可选向后兼容。
 * </p>
 */
@RestController
@RequestMapping("/api/medical-registry")
@RequiredArgsConstructor
public class MedicalRegistryController {

    private final MedicalRegistryReader registryReader;
    private final MedicalRegistryConfig medicalRegistryConfig;
    private final MedicalDdlGenerator ddlGenerator;
    private final MedicalTableProvisioner tableProvisioner;
    private final DatasetCompareService datasetCompareService;

    /**
     * 获取规范数据集摘要列表。
     * registryDsId 为可选参数，如果全局配置已启用则忽略。
     */
    @GetMapping("/datasets")
    public ResponseEntity<ApiResponse<List<DatasetSummary>>> listDatasets(
            @RequestParam(required = false) Long registryDsId) {
        List<DatasetSummary> summaries;
        if (medicalRegistryConfig.isEnabled() && medicalRegistryConfig.isConfigured()) {
            summaries = registryReader.listDatasetSummaries();
        } else if (registryDsId != null) {
            summaries = registryReader.listDatasetSummaries(registryDsId);
        } else {
            return ResponseEntity.badRequest().body(ApiResponse.error("医共体规范库未配置，请在系统设置中配置全局连接信息"));
        }
        return ResponseEntity.ok(ApiResponse.ok(summaries));
    }

    /**
     * 预览某数据集的 DDL，不实际执行。
     */
    @GetMapping("/datasets/{shujujid}/preview-ddl")
    public ResponseEntity<ApiResponse<DdlResult>> previewDdl(
            @PathVariable String shujujid,
            @RequestParam(required = false) Long registryDsId) {
        DatasetDefinition dataset = findDataset(registryDsId, shujujid);
        DdlResult result = ddlGenerator.generateDdl(dataset);
        return ResponseEntity.ok(ApiResponse.ok(result));
    }

    /**
     * 预览某数据集对应的同步任务配置。
     */
    @GetMapping("/datasets/{shujujid}/preview-config")
    public ResponseEntity<ApiResponse<Map<String, Object>>> previewConfig(
            @PathVariable String shujujid,
            @RequestParam(required = false) Long registryDsId) {
        DatasetDefinition dataset = findDataset(registryDsId, shujujid);

        // 从数据集定义推导同步任务配置预览
        List<String> upsertKeys = dataset.fields().stream()
                .filter(FieldDefinition::primaryKey)
                .sorted((a, b) -> Integer.compare(
                        a.shunxuhao() != null ? a.shunxuhao() : 0,
                        b.shunxuhao() != null ? b.shunxuhao() : 0))
                .map(f -> f.ziduandm().toLowerCase())
                .toList();

        boolean hasZuofeibz = dataset.fields().stream()
                .anyMatch(f -> "ZUOFEIBZ".equalsIgnoreCase(f.ziduandm()));

        Map<String, Object> config = new LinkedHashMap<>();
        config.put("upsertKeys", upsertKeys);
        config.put("incrementalField", "XIUGAISJ");
        config.put("sequenceCol", "xiugaisj");
        config.put("dorisTableModel", "UNIQUE_KEY");
        config.put("syncMode", "UPSERT");
        config.put("enableDorisMerge", !hasZuofeibz);

        return ResponseEntity.ok(ApiResponse.ok(config));
    }

    /**
     * 批量建表。
     */
    @PostMapping("/provision")
    public ResponseEntity<ApiResponse<ProvisionResult>> provision(
            @RequestBody ProvisionRequest request) {
        if (request.targetDsId() == null) {
            return ResponseEntity.badRequest().body(ApiResponse.error("targetDsId 不能为空"));
        }

        List<DatasetDefinition> datasets;
        if (medicalRegistryConfig.isEnabled() && medicalRegistryConfig.isConfigured()) {
            datasets = registryReader.loadDatasets();
        } else if (request.registryDsId() != null) {
            datasets = registryReader.loadDatasets(request.registryDsId());
        } else {
            return ResponseEntity.badRequest().body(ApiResponse.error("医共体规范库未配置"));
        }

        List<DdlResult> ddlList = datasets.stream()
                .map(ddlGenerator::generateDdl)
                .toList();
        ProvisionResult result = tableProvisioner.provision(request.targetDsId(), ddlList);
        return ResponseEntity.ok(ApiResponse.ok(result));
    }

    // ── 内部方法 ──

    private DatasetDefinition findDataset(Long registryDsId, String shujujid) {
        List<DatasetDefinition> datasets;
        if (medicalRegistryConfig.isEnabled() && medicalRegistryConfig.isConfigured()) {
            datasets = registryReader.loadDatasets();
        } else if (registryDsId != null) {
            datasets = registryReader.loadDatasets(registryDsId);
        } else {
            throw new IllegalArgumentException("医共体规范库未配置，请在系统设置中配置全局连接信息");
        }
        return datasets.stream()
                .filter(ds -> shujujid.equals(ds.shujujid()))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "数据集不存在: shujujid=" + shujujid));
    }

    // ── 请求体 DTO ──

    public record ProvisionRequest(Long registryDsId, Long targetDsId) {}

    /**
     * 数据集字段对比：规范定义 vs 源端视图。
     */
    @GetMapping("/compare")
    public ResponseEntity<ApiResponse<DatasetCompareResult>> compare(
            @RequestParam(required = false) Long registryDsId,
            @RequestParam Long sourceDsId,
            @RequestParam String datasetCode) {
        if (sourceDsId == null || datasetCode == null) {
            return ResponseEntity.badRequest().body(ApiResponse.error("参数不完整"));
        }
        // registryDsId 可选：全局配置启用时内部会使用全局配置
        if (registryDsId == null && !(medicalRegistryConfig.isEnabled() && medicalRegistryConfig.isConfigured())) {
            return ResponseEntity.badRequest().body(ApiResponse.error("医共体规范库未配置"));
        }
        DatasetCompareResult result = datasetCompareService.compare(registryDsId, sourceDsId, datasetCode);
        return ResponseEntity.ok(ApiResponse.ok(result));
    }
}
