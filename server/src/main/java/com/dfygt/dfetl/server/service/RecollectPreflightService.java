package com.dfygt.dfetl.server.service;

import com.dfygt.dfetl.server.common.ExecutionErrorSanitizer;
import com.dfygt.dfetl.server.dto.ConnectionTestResult;
import com.dfygt.dfetl.server.engine.seatunnel.SeaTunnelConfBuilder;
import com.dfygt.dfetl.server.entity.SyncTask;
import com.dfygt.dfetl.server.medical.mapping.MedicalMappingPrecheckRequest;
import com.dfygt.dfetl.server.medical.mapping.MedicalMappingPrecheckResult;
import com.dfygt.dfetl.server.medical.mapping.MedicalMappingPrecheckStatus;
import com.dfygt.dfetl.server.medical.mapping.MedicalMappingService;
import com.dfygt.dfetl.server.medical.precheck.MedicalPrecheckExecutor;
import com.dfygt.dfetl.server.medical.precheck.MedicalPrecheckOptions;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;

/** 重采的 fail-closed 门禁；所有检查必须发生在 TRUNCATE/DROP 之前。 */
@Service
@RequiredArgsConstructor
public class RecollectPreflightService {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final SyncTaskService syncTaskService;
    private final SharedTargetTableGuard sharedTargetTableGuard;
    private final SeaTunnelConfBuilder seaTunnelConfBuilder;
    private final TargetDataSourceService targetDataSourceService;
    private final MedicalMappingService medicalMappingService;
    private final MedicalPrecheckExecutor medicalPrecheckExecutor;

    public void assertReady(SyncTask task) {
        if (task == null || task.getId() == null) {
            throw new IllegalArgumentException("重采任务不能为空");
        }
        syncTaskService.assertNoRunningExecution(task.getId(), "recollect");
        sharedTargetTableGuard.assertDestructiveOperationSafe(task);

        SeaTunnelConfBuilder.SourceCountResult sourceCount =
                seaTunnelConfBuilder.countSourceRowsWithDiagnostic(task, null);
        if (sourceCount == null || sourceCount.rows() < 0) {
            String detail = sourceCount == null ? "source count unavailable" : sourceCount.errorMessage();
            throw new IllegalStateException("PRECHECK_FAILED: 源端全量范围不可读，已禁止清理目标表: "
                    + ExecutionErrorSanitizer.sanitize(detail));
        }

        ConnectionTestResult targetProbe = targetDataSourceService.testConnection(task.getTargetDataSourceId());
        if (targetProbe == null || !targetProbe.isSuccess()) {
            String detail = targetProbe == null ? "target probe unavailable" : targetProbe.getMessage();
            throw new IllegalStateException("PRECHECK_FAILED: Doris 目标端不可达，已禁止清理目标表: "
                    + ExecutionErrorSanitizer.sanitize(detail));
        }

        assertMedicalDataReady(task);
    }

    public void assertMedicalDataReady(SyncTask task) {
        Map<String, Object> characteristics = parseCharacteristics(task.getDataCharacteristics());
        String mode = stringValue(characteristics.get("medicalMappingMode"));
        if (!"CONTRACT_DRIVEN".equalsIgnoreCase(mode)) {
            return;
        }
        String datasetCode = stringValue(characteristics.get("matchedDatasetCode"));
        Map<String, String> fieldMapping = parseFieldMapping(characteristics.get("fieldMapping"));
        List<String> sourceObjects = task.getViewNames() == null ? List.of() : task.getViewNames();
        if (datasetCode == null || datasetCode.isBlank() || sourceObjects.size() != 1) {
            throw new IllegalStateException(
                    "PRECHECK_BLOCKED: 医共体执行要求一个源对象且 matchedDatasetCode 非空");
        }

        MedicalMappingPrecheckResult result = medicalMappingService.precheckForTask(task,
                new MedicalMappingPrecheckRequest(
                        task.getSourceDataSourceId(),
                        task.getSourceSchema(),
                        sourceObjects.get(0),
                        datasetCode,
                        fieldMapping,
                        // 重采门禁不能只抽样前 10000 行；使用最大扫描上界执行完整源范围检查。
                        new MedicalPrecheckOptions(1, Integer.MAX_VALUE, 60, true)));
        if (result == null || result.status() != MedicalMappingPrecheckStatus.READY) {
            String blockers = result == null || result.findings() == null
                    ? "[]"
                    : result.findings().stream()
                    .map(finding -> finding.code() + ":" + finding.field())
                    .toList()
                    .toString();
            throw new IllegalStateException(
                    "PRECHECK_BLOCKED: 医共体静态预检未通过，已禁止医共体任务执行: " + blockers);
        }
        medicalPrecheckExecutor.assertNoBlockers(
                task.getSourceDataSourceId(),
                result.sourceSchema(),
                result.sourceObject(),
                result.checks());
    }

    private Map<String, Object> parseCharacteristics(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return OBJECT_MAPPER.readValue(json, new TypeReference<>() { });
        } catch (Exception e) {
            throw new IllegalStateException("PRECHECK_BLOCKED: dataCharacteristics 不是合法 JSON", e);
        }
    }

    private String stringValue(Object value) {
        return value == null ? null : String.valueOf(value).trim();
    }

    private Map<String, String> parseFieldMapping(Object value) {
        if (value == null) {
            return Map.of();
        }
        if (!(value instanceof Map<?, ?> raw)) {
            throw new IllegalStateException(
                    "PRECHECK_BLOCKED: dataCharacteristics.fieldMapping 不是对象");
        }
        Map<String, String> mapping = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : raw.entrySet()) {
            String source = stringValue(entry.getKey());
            String contract = stringValue(entry.getValue());
            if (source == null || source.isBlank() || contract == null || contract.isBlank()) {
                throw new IllegalStateException(
                        "PRECHECK_BLOCKED: dataCharacteristics.fieldMapping 包含空字段");
            }
            mapping.put(source, contract);
        }
        return Map.copyOf(mapping);
    }
}
