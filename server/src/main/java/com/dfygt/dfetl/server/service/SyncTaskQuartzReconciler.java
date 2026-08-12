package com.dfygt.dfetl.server.service;

import com.dfygt.dfetl.server.entity.SyncTask;
import com.dfygt.dfetl.server.repository.SyncTaskRepository;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.quartz.SchedulerException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 同步任务 Quartz 业务 trigger 巡检/补偿。
 *
 * <p>这里只负责让 Quartz JobStore 与 {@code sync_task} 中的调度配置保持一致；
 * 不直接执行同步任务，真正触发仍由 Quartz job 入队到执行队列。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SyncTaskQuartzReconciler {

    private final SyncTaskRepository repository;
    private final QuartzSchedulerService quartzSchedulerService;

    public void reconcile(SyncTask task) throws SchedulerException {
        if (task == null || task.getId() == null) {
            return;
        }
        String cron = resolveCron(task);
        boolean shouldSchedule = "ENABLED".equals(task.getStatus()) && cron != null && !cron.isBlank();
        if (shouldSchedule) {
            quartzSchedulerService.scheduleTask(task.getId(), cron);
        } else {
            quartzSchedulerService.deleteTask(task.getId());
        }
    }

    @PostConstruct
    public void reconcileAllOnStartupHook() {
        reconcileAllOnStartup();
    }

    public ReconcileResult reconcileAllOnStartup() {
        return reconcileAll("startup");
    }

    @Scheduled(fixedDelayString = "${dfetl.scheduler.sync-reconcile-delay-ms:300000}",
            initialDelayString = "${dfetl.scheduler.sync-reconcile-initial-ms:300000}")
    public void reconcileAllPeriodically() {
        reconcileAll("periodic");
    }

    private ReconcileResult reconcileAll(String phase) {
        List<SyncTask> tasks = repository.findAll();
        int success = 0;
        int failed = 0;
        int skipped = 0;
        for (SyncTask task : tasks) {
            if (task == null || task.getId() == null) {
                skipped++;
                continue;
            }
            try {
                reconcile(task);
                success++;
            } catch (Exception e) {
                failed++;
                log.warn("SyncTaskQuartzReconciler: {} reconcile failed taskId={}: {}",
                        phase, task.getId(), e.getMessage());
            }
        }
        log.info("SyncTaskQuartzReconciler: {} reconcile complete, success={} failed={} skipped={}",
                phase, success, failed, skipped);
        return new ReconcileResult(success, failed, skipped);
    }

    private static String resolveCron(SyncTask task) {
        String cron = task.getCronExpression();
        if (cron == null || cron.isBlank()) {
            cron = task.getSchedule();
        }
        return cron;
    }

    public record ReconcileResult(int success, int failed, int skipped) {
    }
}
