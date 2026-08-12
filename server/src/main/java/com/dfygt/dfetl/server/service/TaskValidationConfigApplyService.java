package com.dfygt.dfetl.server.service;

import com.dfygt.dfetl.server.dto.TaskValidationConfigDto;
import lombok.RequiredArgsConstructor;
import org.quartz.CronExpression;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.math.BigDecimal;
import java.util.Locale;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class TaskValidationConfigApplyService {

    private final TaskValidationProfileService profileService;
    private final ValidationRiskPolicy riskPolicy;
    private final DriftWatchScheduler driftWatchScheduler;
    private static final Set<String> CHECKSUM_ALGOS = Set.of("XXHASH64", "MD5", "SHA256", "CRC32");
    private static final Set<String> CHECKSUM_SCOPES = Set.of("FULL", "WINDOW");

    public TaskValidationConfigDto saveForNewTask(Long taskId, TaskValidationConfigDto dto) {
        validateConfig(dto);
        riskPolicy.validate(taskId, dto);
        TaskValidationConfigDto saved = profileService.save(taskId, dto);
        reconcileDriftWatchAfterCommit(taskId, Boolean.TRUE.equals(saved.getEnabled()), saved.getDriftCron());
        return saved;
    }

    void validateConfig(TaskValidationConfigDto dto) {
        if (dto == null) {
            return;
        }
        if (dto.getValidationLookbackHours() != null
                && (dto.getValidationLookbackHours() < 0 || dto.getValidationLookbackHours() > 168)) {
            throw new IllegalArgumentException("validationLookbackHours 必须在 0~168 小时范围内；"
                    + "null=继承全局，0=只验本次窗口");
        }
        if (dto.getChecksumAlgo() != null && !dto.getChecksumAlgo().isBlank()
                && !CHECKSUM_ALGOS.contains(dto.getChecksumAlgo().trim().toUpperCase(Locale.ROOT))) {
            throw new IllegalArgumentException("checksumAlgo 仅支持 XXHASH64 / MD5 / SHA256 / CRC32");
        }
        if (dto.getChecksumScope() != null && !dto.getChecksumScope().isBlank()
                && !CHECKSUM_SCOPES.contains(dto.getChecksumScope().trim().toUpperCase(Locale.ROOT))) {
            throw new IllegalArgumentException("checksumScope 仅支持 FULL / WINDOW");
        }
        if (dto.getSampleRate() != null
                && (dto.getSampleRate().compareTo(BigDecimal.ZERO) <= 0
                || dto.getSampleRate().compareTo(new BigDecimal("100")) > 0)) {
            throw new IllegalArgumentException("sampleRate 必须在 (0,100] 范围内");
        }
        if (dto.getToleranceRows() != null && dto.getToleranceRows() < 0) {
            throw new IllegalArgumentException("toleranceRows 不能为负数");
        }
        if (dto.getTolerancePct() != null
                && (dto.getTolerancePct().compareTo(BigDecimal.ZERO) < 0
                || dto.getTolerancePct().compareTo(new BigDecimal("100")) > 0)) {
            throw new IllegalArgumentException("tolerancePct 必须在 [0,100] 范围内");
        }
        if (dto.getAutoRepairMaxRows() != null && dto.getAutoRepairMaxRows() <= 0) {
            throw new IllegalArgumentException("autoRepairMaxRows 必须大于 0");
        }
        if (dto.getDriftCron() != null && !dto.getDriftCron().isBlank()) {
            String raw = dto.getDriftCron().trim();
            try {
                String quartzCron = QuartzSchedulerService.normalizeCronExpression(raw);
                if (!CronExpression.isValidExpression(quartzCron)) {
                    throw new IllegalArgumentException("driftCron 不是合法的 cron 表达式: " + raw);
                }
            } catch (IllegalArgumentException e) {
                throw e;
            } catch (Exception e) {
                throw new IllegalArgumentException("driftCron 解析失败: " + e.getMessage(), e);
            }
        }
    }

    private void reconcileDriftWatchAfterCommit(Long taskId, boolean enabled, String driftCron) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    driftWatchScheduler.reconcile(taskId, enabled, driftCron);
                }
            });
        } else {
            driftWatchScheduler.reconcile(taskId, enabled, driftCron);
        }
    }
}
