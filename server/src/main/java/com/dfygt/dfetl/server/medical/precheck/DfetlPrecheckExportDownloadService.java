package com.dfygt.dfetl.server.medical.precheck;

import com.dfygt.dfetl.server.entity.AuditLog;
import com.dfygt.dfetl.server.entity.DfetlPrecheckExport;
import com.dfygt.dfetl.server.entity.DfetlPrecheckRun;
import com.dfygt.dfetl.server.entity.Institution;
import com.dfygt.dfetl.server.external.security.ExternalApiAuthorizationService;
import com.dfygt.dfetl.server.repository.AuditLogRepository;
import com.dfygt.dfetl.server.repository.DfetlPrecheckExportRepository;
import com.dfygt.dfetl.server.repository.DfetlPrecheckRunRepository;
import com.dfygt.dfetl.server.repository.InstitutionRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.NoSuchElementException;

/** 授权打开受控本地导出文件，并显式记录 GET 下载审计。 */
@Service
public class DfetlPrecheckExportDownloadService {

    private final DfetlPrecheckExportRepository exportRepository;
    private final DfetlPrecheckRunRepository runRepository;
    private final InstitutionRepository institutionRepository;
    private final ExternalApiAuthorizationService authorizationService;
    private final AuditLogRepository auditLogRepository;
    private final ObjectMapper objectMapper;
    private final Path exportRoot;

    public DfetlPrecheckExportDownloadService(
            DfetlPrecheckExportRepository exportRepository,
            DfetlPrecheckRunRepository runRepository,
            InstitutionRepository institutionRepository,
            ExternalApiAuthorizationService authorizationService,
            AuditLogRepository auditLogRepository,
            ObjectMapper objectMapper,
            @Value("${dfetl.data-precheck.export.local-root:/var/lib/dfetl/precheck-exports}")
            String exportRoot) {
        this.exportRepository = exportRepository;
        this.runRepository = runRepository;
        this.institutionRepository = institutionRepository;
        this.authorizationService = authorizationService;
        this.auditLogRepository = auditLogRepository;
        this.objectMapper = objectMapper;
        this.exportRoot = Path.of(exportRoot).toAbsolutePath().normalize();
    }

    public DownloadFile open(Long exportId, int fileIndex) {
        if (exportId == null || exportId <= 0 || fileIndex < 0) {
            throw new IllegalArgumentException("exportId 和 fileIndex 必须有效");
        }
        DfetlPrecheckExport export = exportRepository.findById(exportId)
                .orElseThrow(() -> new NoSuchElementException("数据预检导出不存在: " + exportId));
        authorize(export.getRunId());
        if (!"COMPLETED".equals(export.getStatus())) {
            throw new IllegalStateException("只有已完成的导出可以下载: " + export.getStatus());
        }
        if (export.getExpiresAt() != null && !export.getExpiresAt().isAfter(Instant.now())) {
            throw new IllegalStateException("数据预检导出已过期");
        }
        try {
            List<DorisPrecheckExportService.ExportFile> files = objectMapper.readValue(
                    export.getFileManifest(), new TypeReference<>() {});
            if (fileIndex >= files.size()) {
                throw new NoSuchElementException("数据预检导出分片不存在: " + fileIndex);
            }
            DorisPrecheckExportService.ExportFile manifest = files.get(fileIndex);
            if (!"LOCAL".equals(manifest.storage())) {
                throw new IllegalStateException("远程 OUTFILE 文件由对象存储生命周期管理，不支持本地下载");
            }
            Path exportDirectory = exportRoot.resolve(String.valueOf(exportId)).normalize();
            Path file = exportRoot.resolve(manifest.path()).normalize();
            if (!exportDirectory.startsWith(exportRoot) || !file.startsWith(exportDirectory)) {
                throw new IllegalStateException("导出文件路径越界");
            }
            if (!Files.isRegularFile(file)) {
                throw new NoSuchElementException("数据预检导出文件不存在");
            }
            recordAudit(export, manifest.name(), fileIndex);
            return new DownloadFile(
                    file, safeName(manifest.name()), manifest.contentType(), Files.size(file));
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("读取数据预检导出清单失败", e);
        }
    }

    private void authorize(Long runId) {
        DfetlPrecheckRun run = runRepository.findById(runId)
                .orElseThrow(() -> new NoSuchElementException("数据预检运行不存在: " + runId));
        Institution institution = institutionRepository.findById(run.getInstitutionId())
                .orElseThrow(() -> new NoSuchElementException("机构不存在: " + run.getInstitutionId()));
        authorizationService.assertAllowed(institution.getCode());
    }

    private void recordAudit(
            DfetlPrecheckExport export, String fileName, int fileIndex) {
        AuditLog audit = new AuditLog();
        audit.setActionTime(Instant.now());
        audit.setUserName(currentUsername());
        audit.setAction("下载数据预检导出");
        audit.setTargetType("dfetl_precheck_export");
        audit.setTargetId(export.getId());
        audit.setTargetName(safeName(fileName));
        audit.setDetail("runId=" + export.getRunId() + ", fileIndex=" + fileIndex);
        auditLogRepository.save(audit);
    }

    private static String currentUsername() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()
                || "anonymousUser".equals(authentication.getPrincipal())) {
            return "system";
        }
        return authentication.getName();
    }

    private static String safeName(String value) {
        if (value == null || value.isBlank()) {
            return "precheck-export";
        }
        return value.replace("\\", "_").replace("/", "_")
                .replace("\r", "_").replace("\n", "_");
    }

    public record DownloadFile(Path path, String name, String contentType, long bytes) {
    }
}
