package com.dfygt.dfetl.server.medical.precheck;

import com.dfygt.dfetl.server.dto.DfetlPrecheckExportDto;
import com.dfygt.dfetl.server.dto.DfetlPrecheckExportRequest;
import com.dfygt.dfetl.server.entity.DfetlPrecheckExport;
import com.dfygt.dfetl.server.entity.DfetlPrecheckRun;
import com.dfygt.dfetl.server.entity.Institution;
import com.dfygt.dfetl.server.external.security.ExternalApiAuthorizationService;
import com.dfygt.dfetl.server.repository.DfetlPrecheckExportRepository;
import com.dfygt.dfetl.server.repository.DfetlPrecheckRunRepository;
import com.dfygt.dfetl.server.repository.InstitutionRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.UUID;

/** 创建异步导出元数据；文件生成和状态推进由 Phase 6 导出 Worker 完成。 */
@Service
public class DfetlPrecheckExportService {

    private static final Set<String> EXPORTABLE_STATUSES = Set.of("HAS_ERRORS", "PASSED");

    private final DfetlPrecheckExportRepository exportRepository;
    private final DfetlPrecheckRunRepository runRepository;
    private final InstitutionRepository institutionRepository;
    private final ExternalApiAuthorizationService authorizationService;
    private final ObjectMapper objectMapper;
    private final DfetlPrecheckExportWorker exportWorker;
    private final int retentionDays;

    public DfetlPrecheckExportService(
            DfetlPrecheckExportRepository exportRepository,
            DfetlPrecheckRunRepository runRepository,
            InstitutionRepository institutionRepository,
            ExternalApiAuthorizationService authorizationService,
            ObjectMapper objectMapper,
            DfetlPrecheckExportWorker exportWorker,
            @Value("${dfetl.data-precheck.export.retention-days:7}") int retentionDays) {
        if (retentionDays <= 0) {
            throw new IllegalArgumentException("预检导出保留天数必须大于 0");
        }
        this.exportRepository = exportRepository;
        this.runRepository = runRepository;
        this.institutionRepository = institutionRepository;
        this.authorizationService = authorizationService;
        this.objectMapper = objectMapper;
        this.exportWorker = exportWorker;
        this.retentionDays = retentionDays;
    }

    public DfetlPrecheckExportDto create(Long runId, DfetlPrecheckExportRequest request) {
        DfetlPrecheckRun run = authorizedRun(runId);
        if (!EXPORTABLE_STATUSES.contains(run.getStatus())) {
            throw new IllegalStateException("只有已完成数据校验的预检运行可以导出: " + run.getStatus());
        }
        DfetlPrecheckExportRequest normalized = request == null
                ? new DfetlPrecheckExportRequest("CSV", null, null, null, null)
                : request;
        try {
            DfetlPrecheckExport export = DfetlPrecheckExport.pending(
                    run.getId(), UUID.randomUUID().toString(),
                    objectMapper.writeValueAsString(normalized.toIssueQuery()),
                    normalized.format(), currentUsername());
            export.setExpiresAt(Instant.now().plus(retentionDays, ChronoUnit.DAYS));
            DfetlPrecheckExport saved = exportRepository.save(export);
            exportWorker.enqueue(saved.getId());
            return DfetlPrecheckExportDto.from(saved);
        } catch (Exception e) {
            if (e instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            throw new IllegalStateException("创建数据预检导出任务失败", e);
        }
    }

    public DfetlPrecheckExportDto get(Long exportId) {
        if (exportId == null || exportId <= 0) {
            throw new IllegalArgumentException("exportId 必须为正整数");
        }
        DfetlPrecheckExport export = exportRepository.findById(exportId)
                .orElseThrow(() -> new NoSuchElementException("数据预检导出不存在: " + exportId));
        authorizedRun(export.getRunId());
        return DfetlPrecheckExportDto.from(export);
    }

    private DfetlPrecheckRun authorizedRun(Long runId) {
        if (runId == null || runId <= 0) {
            throw new IllegalArgumentException("runId 必须为正整数");
        }
        DfetlPrecheckRun run = runRepository.findById(runId)
                .orElseThrow(() -> new NoSuchElementException("数据预检运行不存在: " + runId));
        Institution institution = institutionRepository.findById(run.getInstitutionId())
                .orElseThrow(() -> new NoSuchElementException("机构不存在: " + run.getInstitutionId()));
        authorizationService.assertAllowed(institution.getCode());
        return run;
    }

    private static String currentUsername() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()
                || "anonymousUser".equals(authentication.getPrincipal())) {
            return "system";
        }
        return authentication.getName();
    }
}
