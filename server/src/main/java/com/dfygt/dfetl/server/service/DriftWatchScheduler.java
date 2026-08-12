package com.dfygt.dfetl.server.service;

import com.dfygt.dfetl.server.entity.TaskValidationConfig;
import com.dfygt.dfetl.server.repository.TaskValidationConfigRepository;
import com.dfygt.dfetl.server.repository.SyncTaskRepository;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class DriftWatchScheduler {

    private final TaskValidationConfigRepository repo;
    private final SyncTaskRepository syncTaskRepository;
    private final QuartzSchedulerService quartzSchedulerService;

    public void reconcile(Long taskId, boolean enabled, String driftCron) {
        if (taskId == null) {
            return;
        }
        boolean taskEnabled = syncTaskRepository.findById(taskId)
                .map(task -> task.getStatus() == null || "ENABLED".equalsIgnoreCase(task.getStatus())
                        || "ACTIVE".equalsIgnoreCase(task.getStatus()))
                .orElse(false);
        boolean wantSchedule = taskEnabled && enabled && driftCron != null && !driftCron.isBlank();
        try {
            if (wantSchedule) {
                quartzSchedulerService.scheduleDriftWatch(taskId, driftCron);
            } else {
                quartzSchedulerService.deleteDriftWatch(taskId);
            }
        } catch (org.quartz.SchedulerException e) {
            throw new RuntimeException("DriftWatch cron 更新失败 taskId=" + taskId + ": " + e.getMessage(), e);
        }
    }

    public void delete(Long taskId) {
        quartzSchedulerService.deleteDriftWatch(taskId);
    }

    @PostConstruct
    public void reconcileAllOnStartup() {
        List<TaskValidationConfig> all = repo.findAll();
        int success = 0, failed = 0;
        for (TaskValidationConfig config : all) {
            try {
                reconcile(config.getTaskId(), Boolean.TRUE.equals(config.getEnabled()), config.getDriftCron());
                success++;
            } catch (Exception e) {
                failed++;
                log.warn("DriftWatchScheduler: startup reconcile failed taskId={}: {}", config.getTaskId(), e.getMessage());
            }
        }
        cleanupOrphans("startup");
        log.info("DriftWatchScheduler: startup reconcile complete, success={} failed={}", success, failed);
    }

    @Scheduled(fixedDelayString = "${dfetl.validation.drift-reconcile-delay-ms:300000}",
            initialDelayString = "${dfetl.validation.drift-reconcile-initial-ms:300000}")
    public void reconcileAllPeriodically() {
        List<TaskValidationConfig> all = repo.findAll();
        for (TaskValidationConfig config : all) {
            try {
                reconcile(config.getTaskId(), Boolean.TRUE.equals(config.getEnabled()), config.getDriftCron());
            } catch (Exception e) {
                log.debug("DriftWatchScheduler: periodic reconcile failed taskId={}: {}", config.getTaskId(), e.getMessage());
            }
        }
        cleanupOrphans("periodic");
    }

    /** 只保留“任务存在、配置启用且有 cron”的 drift-watch 调度。 */
    private void cleanupOrphans(String phase) {
        try {
            Set<Long> existingTaskIds = syncTaskRepository.findAll().stream()
                    .map(task -> task.getId())
                    .filter(id -> id != null)
                    .collect(Collectors.toSet());
            Set<Long> validTaskIds = repo.findAll().stream()
                    .filter(config -> config.getTaskId() != null)
                    .filter(config -> existingTaskIds.contains(config.getTaskId()))
                    .filter(config -> syncTaskRepository.findById(config.getTaskId())
                            .map(task -> task.getStatus() == null || "ENABLED".equalsIgnoreCase(task.getStatus())
                                    || "ACTIVE".equalsIgnoreCase(task.getStatus()))
                            .orElse(false))
                    .filter(config -> Boolean.TRUE.equals(config.getEnabled()))
                    .filter(config -> config.getDriftCron() != null && !config.getDriftCron().isBlank())
                    .map(TaskValidationConfig::getTaskId)
                    .collect(Collectors.toSet());
            int deleted = quartzSchedulerService.deleteOrphanDriftWatch(validTaskIds);
            if (deleted > 0) {
                log.info("DriftWatchScheduler: {} orphan cleanup deleted={} valid={}",
                        phase, deleted, validTaskIds.size());
            }
        } catch (Exception e) {
            // 数据库/Quartz 不可用时 fail-safe：只记录，不执行破坏性删除。
            log.warn("DriftWatchScheduler: {} orphan cleanup failed: {}", phase, e.getMessage(), e);
        }
    }
}
