package com.dfygt.dfetl.server.medical.precheck;

import com.dfygt.dfetl.server.common.AesUtil;
import com.dfygt.dfetl.server.common.IdentifierSanitizer;
import com.dfygt.dfetl.server.common.JdbcConnectionPoolManager;
import com.dfygt.dfetl.server.entity.DfetlPrecheckExport;
import com.dfygt.dfetl.server.entity.DfetlPrecheckRun;
import com.dfygt.dfetl.server.entity.InstitutionDatasetRoute;
import com.dfygt.dfetl.server.entity.TargetDataSource;
import com.dfygt.dfetl.server.repository.DfetlPrecheckExportRepository;
import com.dfygt.dfetl.server.repository.DfetlPrecheckRunRepository;
import com.dfygt.dfetl.server.repository.InstitutionDatasetRouteRepository;
import com.dfygt.dfetl.server.repository.TargetDataSourceRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.List;
import java.util.NoSuchElementException;

/** 按运行批次精确清理原始暂存，并清理过期的受控本地导出。 */
@Service
@Slf4j
public class DorisPrecheckCleanupService {

    private static final List<String> EXPIRABLE_EXPORT_STATUSES =
            List.of("COMPLETED", "FAILED", "CANCELLED");

    private final DfetlPrecheckRunRepository runRepository;
    private final DfetlPrecheckExportRepository exportRepository;
    private final InstitutionDatasetRouteRepository routeRepository;
    private final TargetDataSourceRepository targetRepository;
    private final AesUtil aesUtil;
    private final JdbcConnectionPoolManager connectionPoolManager;
    private final ObjectMapper objectMapper;
    private final Path exportRoot;
    private final int passedRawRetentionDays;
    private final int errorRawRetentionDays;

    public DorisPrecheckCleanupService(
            DfetlPrecheckRunRepository runRepository,
            DfetlPrecheckExportRepository exportRepository,
            InstitutionDatasetRouteRepository routeRepository,
            TargetDataSourceRepository targetRepository,
            AesUtil aesUtil,
            JdbcConnectionPoolManager connectionPoolManager,
            ObjectMapper objectMapper,
            @Value("${dfetl.data-precheck.export.local-root:/var/lib/dfetl/precheck-exports}")
            String exportRoot,
            @Value("${dfetl.data-precheck.cleanup.passed-raw-retention-days:1}")
            int passedRawRetentionDays,
            @Value("${dfetl.data-precheck.cleanup.error-raw-retention-days:30}")
            int errorRawRetentionDays) {
        if (passedRawRetentionDays <= 0 || errorRawRetentionDays <= 0) {
            throw new IllegalArgumentException("数据预检暂存保留天数必须大于 0");
        }
        this.runRepository = runRepository;
        this.exportRepository = exportRepository;
        this.routeRepository = routeRepository;
        this.targetRepository = targetRepository;
        this.aesUtil = aesUtil;
        this.connectionPoolManager = connectionPoolManager;
        this.objectMapper = objectMapper;
        this.exportRoot = Path.of(exportRoot).toAbsolutePath().normalize();
        this.passedRawRetentionDays = passedRawRetentionDays;
        this.errorRawRetentionDays = errorRawRetentionDays;
    }

    @Scheduled(
            cron = "${dfetl.data-precheck.cleanup.cron:0 30 3 * * ?}",
            zone = "${dfetl.data-precheck.cleanup.zone:Asia/Shanghai}")
    public void cleanupExpired() {
        Instant now = Instant.now();
        cleanupRuns(runRepository
                .findTop100ByStatusAndRawCleanedAtIsNullAndFinishedAtBeforeOrderByFinishedAtAsc(
                        "PASSED",
                        now.minus(passedRawRetentionDays, ChronoUnit.DAYS)));
        cleanupRuns(runRepository
                .findTop100ByStatusAndRawCleanedAtIsNullAndFinishedAtBeforeOrderByFinishedAtAsc(
                        "HAS_ERRORS",
                        now.minus(errorRawRetentionDays, ChronoUnit.DAYS)));
        for (DfetlPrecheckExport export : exportRepository
                .findTop100ByStatusInAndExpiresAtBeforeOrderByExpiresAtAsc(
                        EXPIRABLE_EXPORT_STATUSES, now)) {
            try {
                cleanupExport(export);
            } catch (Exception e) {
                log.warn("清理数据预检导出失败: exportId={}, error={}",
                        export.getId(), e.getMessage());
            }
        }
    }

    void cleanupRawRun(DfetlPrecheckRun run) {
        if (run == null || run.getId() == null || run.getDatasetId() == null) {
            throw new IllegalArgumentException("预检运行及其数据集必须有效");
        }
        String staging = run.getStagingTable();
        int separator = staging == null ? -1 : staging.indexOf('.');
        if (separator <= 0 || separator == staging.length() - 1) {
            throw new IllegalStateException("预检运行缺少可解析的 Doris 暂存表");
        }
        String database = IdentifierSanitizer.requireValid(
                staging.substring(0, separator), "precheckDatabase");
        String rawTable = IdentifierSanitizer.requireValid(
                staging.substring(separator + 1), "precheckRawTable");
        InstitutionDatasetRoute route = routeRepository.findById(run.getRouteId())
                .orElseThrow(() -> new NoSuchElementException(
                        "机构数据集路由不存在: " + run.getRouteId()));
        String expectedRawTable = DorisPrecheckTableSpec.rawTableForDatasetCode(
                route.getTargetTable());
        if (!expectedRawTable.equals(rawTable)) {
            throw new IllegalStateException("预检暂存表与数据集不匹配");
        }
        TargetDataSource target = targetRepository.findById(route.getTargetDatasourceId())
                .orElseThrow(() -> new NoSuchElementException(
                        "目标数据源不存在: " + route.getTargetDatasourceId()));
        if (!"NORMAL".equals(target.getStatus())) {
            throw new IllegalStateException("目标数据源不可用于预检清理: " + target.getStatus());
        }
        String sql = "DELETE FROM `" + database + "`.`" + rawTable + "` WHERE run_id=?";
        try (Connection connection = openConnection(target);
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, run.getId());
            statement.executeUpdate();
            runRepository.markRawCleaned(run.getId(), Instant.now());
        } catch (Exception e) {
            throw new IllegalStateException("清理 Doris 预检暂存批次失败", e);
        }
    }

    private void cleanupRuns(List<DfetlPrecheckRun> runs) {
        for (DfetlPrecheckRun run : runs) {
            try {
                cleanupRawRun(run);
            } catch (Exception e) {
                log.warn("清理 Doris 预检暂存批次失败: runId={}, error={}",
                        run.getId(), e.getMessage());
            }
        }
    }

    private void cleanupExport(DfetlPrecheckExport export) throws Exception {
        List<DorisPrecheckExportService.ExportFile> files = objectMapper.readValue(
                export.getFileManifest(), new TypeReference<>() {});
        Path exportDirectory = safeExportDirectory(export.getId());
        for (DorisPrecheckExportService.ExportFile file : files) {
            if (!"LOCAL".equals(file.storage())) {
                continue;
            }
            Path local = exportRoot.resolve(file.path()).normalize();
            if (!local.startsWith(exportDirectory)) {
                throw new IllegalStateException("导出文件路径越界");
            }
            Files.deleteIfExists(local);
        }
        deleteExactDirectory(exportDirectory);
        export.setStatus("EXPIRED");
        exportRepository.save(export);
    }

    private Path safeExportDirectory(Long exportId) {
        Path directory = exportRoot.resolve(String.valueOf(exportId)).normalize();
        if (!directory.startsWith(exportRoot)) {
            throw new IllegalStateException("导出清理路径越界");
        }
        return directory;
    }

    private void deleteExactDirectory(Path directory) throws IOException {
        if (!Files.exists(directory)) {
            return;
        }
        try (var paths = Files.walk(directory)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }

    private Connection openConnection(TargetDataSource target) throws Exception {
        int port = target.getFePort() == null ? 9030 : target.getFePort();
        String url = "jdbc:mysql://" + target.getFeHost() + ":" + port + "/" + target.getDbName()
                + "?useSSL=false&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true";
        return connectionPoolManager.getConnection(
                url, target.getUsername(), aesUtil.decrypt(target.getPasswordEnc()));
    }
}
