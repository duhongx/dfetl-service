package com.dfygt.dfetl.server.medical.precheck;

import com.dfygt.dfetl.server.entity.DfetlPrecheckExport;
import com.dfygt.dfetl.server.repository.DfetlPrecheckExportRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

/** 预检导出的有界异步队列和重启恢复器。 */
@Service
@Slf4j
public class DfetlPrecheckExportWorker {

    private final DfetlPrecheckExportRepository exportRepository;
    private final DorisPrecheckExportService generator;
    private final Executor executor;
    private final int runningTimeoutMinutes;

    public DfetlPrecheckExportWorker(
            DfetlPrecheckExportRepository exportRepository,
            DorisPrecheckExportService generator,
            @Qualifier("precheckExportExecutor") Executor executor,
            @Value("${dfetl.data-precheck.export.running-timeout-minutes:120}")
            int runningTimeoutMinutes) {
        if (runningTimeoutMinutes <= 0) {
            throw new IllegalArgumentException("导出运行超时分钟数必须大于 0");
        }
        this.exportRepository = exportRepository;
        this.generator = generator;
        this.executor = executor;
        this.runningTimeoutMinutes = runningTimeoutMinutes;
    }

    public void enqueue(Long exportId) {
        try {
            executor.execute(() -> generator.generate(exportId));
        } catch (RejectedExecutionException e) {
            log.warn("数据预检导出队列已满，保留 PENDING 等待恢复: exportId={}", exportId);
        }
    }

    @Scheduled(
            fixedDelayString = "${dfetl.data-precheck.export.recovery-delay-ms:60000}",
            initialDelayString = "${dfetl.data-precheck.export.recovery-initial-delay-ms:10000}")
    public void recover() {
        Instant now = Instant.now();
        int interrupted = exportRepository.failInterruptedRunning(
                now.minus(runningTimeoutMinutes, ChronoUnit.MINUTES), now);
        if (interrupted > 0) {
            log.warn("已终止 {} 个超时或中断的数据预检导出", interrupted);
        }
        for (DfetlPrecheckExport export
                : exportRepository.findTop20ByStatusOrderByCreatedAtAsc("PENDING")) {
            enqueue(export.getId());
        }
    }
}
