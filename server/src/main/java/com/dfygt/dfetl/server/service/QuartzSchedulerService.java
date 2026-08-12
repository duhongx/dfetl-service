package com.dfygt.dfetl.server.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.quartz.*;
import org.quartz.impl.matchers.GroupMatcher;
import org.springframework.stereotype.Service;

import java.util.Set;
import java.util.TimeZone;

/**
 * Quartz 调度器门面。
 * <p>
 * 动态注册、更新、暂停、恢复、删除同步任务的 cron 触发器。
 * 一个 SyncTask 对应一个 JobKey（group="sync-task", name=taskId）。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class QuartzSchedulerService {

    private final Scheduler scheduler;

    private static final String GROUP = "sync-task";

    /**
     * 注册或更新一个任务的 cron 调度计划。
     * cron 表达式存储在 sync_task.schedule 字段，此处直接接收。
     * 兼容 Linux 5 字段格式（自动补秒位 0 转为 Quartz 6 字段）。
     */
    public void scheduleTask(Long taskId, String cronExpression) throws SchedulerException {
        String quartzCron = normalizeCronExpression(cronExpression);
        JobKey jobKey = JobKey.jobKey(String.valueOf(taskId), GROUP);
        TriggerKey triggerKey = TriggerKey.triggerKey(String.valueOf(taskId), GROUP);

        JobDetail job = JobBuilder.newJob(SyncTaskQuartzJob.class)
                .withIdentity(jobKey)
                .usingJobData("taskId", taskId)
                .storeDurably()
                .build();

        CronTrigger trigger = TriggerBuilder.newTrigger()
                .withIdentity(triggerKey)
                .forJob(jobKey)
                .withSchedule(CronScheduleBuilder
                        .cronSchedule(quartzCron)
                        .inTimeZone(TimeZone.getTimeZone("Asia/Shanghai"))
                        .withMisfireHandlingInstructionDoNothing())  // 错过不补跑
                .build();

        if (scheduler.checkExists(jobKey)) {
            scheduler.addJob(job, true);                  // 更新 JobDetail
            scheduler.rescheduleJob(triggerKey, trigger); // 更新 Trigger
            log.info("QuartzSchedulerService: rescheduled taskId={} cron={} (normalized: {})", taskId, cronExpression, quartzCron);
        } else {
            scheduler.scheduleJob(job, trigger);
            log.info("QuartzSchedulerService: scheduled taskId={} cron={} (normalized: {})", taskId, cronExpression, quartzCron);
        }
    }

    /**
     * 标准化 cron 表达式为 Quartz 兼容格式。
     * Linux 5 字段（分 时 日 月 周） → Quartz 6 字段（秒 分 时 日 月 周），自动补 "0" 秒位。
     * Quartz 要求"日"和"周"中只能有一个具体值，另一个必须为 "?"，自动转换 "*"→"?"。
     * 已是 6/7 字段则原样返回。
     */
    static String normalizeCronExpression(String cron) {
        if (cron == null || cron.isBlank()) {
            return cron;
        }
        String[] parts = cron.trim().split("\\s+");
        if (parts.length == 5) {
            // Linux 5 字段 → Quartz 6 字段：补秒位 + 处理 day-of-month / day-of-week 互斥
            String dayOfMonth = parts[2];
            String dayOfWeek  = parts[4];
            // Quartz 不允许 day-of-month 与 day-of-week 同时为具体值或同时为 *
            // 规则：如果 day-of-week 是 *，转成 ?；否则把 day-of-month 转成 ?
            if ("*".equals(dayOfWeek)) {
                dayOfWeek = "?";
            } else if ("*".equals(dayOfMonth)) {
                dayOfMonth = "?";
            }
            return "0 " + parts[0] + " " + parts[1] + " " + dayOfMonth + " " + parts[3] + " " + dayOfWeek;
        }
        return cron;
    }


    /** 删除任务调度（删除同步任务时调用） */
    public void deleteTask(Long taskId) throws SchedulerException {
        JobKey jobKey = JobKey.jobKey(String.valueOf(taskId), GROUP);
        if (scheduler.checkExists(jobKey)) {
            scheduler.deleteJob(jobKey);
            log.info("QuartzSchedulerService: deleted taskId={}", taskId);
        }
    }

    /** 暂停任务触发 */
    public void pauseTask(Long taskId) throws SchedulerException {
        scheduler.pauseJob(JobKey.jobKey(String.valueOf(taskId), GROUP));
        log.info("QuartzSchedulerService: paused taskId={}", taskId);
    }

    /** 恢复任务触发 */
    public void resumeTask(Long taskId) throws SchedulerException {
        scheduler.resumeJob(JobKey.jobKey(String.valueOf(taskId), GROUP));
        log.info("QuartzSchedulerService: resumed taskId={}", taskId);
    }

    /** 任务是否已在 Quartz 中注册 */
    public boolean isScheduled(Long taskId) throws SchedulerException {
        return scheduler.checkExists(JobKey.jobKey(String.valueOf(taskId), GROUP));
    }

    // ── spec 020.2: snapshot detect 调度 ─────────────────────────────────────

    private static final String SNAPSHOT_DETECT_GROUP = "snapshot-detect";

    /** 注册或更新 snapshot 自动 detect 调度。group 与业务 cron 隔离。 */
    public void scheduleSnapshotDetect(Long taskId, String cronExpression) throws SchedulerException {
        String quartzCron = normalizeCronExpression(cronExpression);
        JobKey jobKey = JobKey.jobKey(String.valueOf(taskId), SNAPSHOT_DETECT_GROUP);
        TriggerKey triggerKey = TriggerKey.triggerKey(String.valueOf(taskId), SNAPSHOT_DETECT_GROUP);

        JobDetail job = JobBuilder.newJob(SnapshotDetectQuartzJob.class)
                .withIdentity(jobKey)
                .usingJobData("taskId", taskId)
                .storeDurably()
                .build();

        CronTrigger trigger = TriggerBuilder.newTrigger()
                .withIdentity(triggerKey)
                .forJob(jobKey)
                .withSchedule(CronScheduleBuilder
                        .cronSchedule(quartzCron)
                        .inTimeZone(TimeZone.getTimeZone("Asia/Shanghai"))
                        .withMisfireHandlingInstructionDoNothing())
                .build();

        if (scheduler.checkExists(jobKey)) {
            scheduler.addJob(job, true);
            scheduler.rescheduleJob(triggerKey, trigger);
            log.info("QuartzSchedulerService: rescheduled snapshot-detect taskId={} cron={} (normalized: {})",
                    taskId, cronExpression, quartzCron);
        } else {
            scheduler.scheduleJob(job, trigger);
            log.info("QuartzSchedulerService: scheduled snapshot-detect taskId={} cron={} (normalized: {})",
                    taskId, cronExpression, quartzCron);
        }
    }

    /** 删除 snapshot 自动 detect 调度。 */
    public void deleteSnapshotDetect(Long taskId) {
        try {
            JobKey jobKey = JobKey.jobKey(String.valueOf(taskId), SNAPSHOT_DETECT_GROUP);
            if (scheduler.checkExists(jobKey)) {
                scheduler.deleteJob(jobKey);
                log.info("QuartzSchedulerService: deleted snapshot-detect taskId={}", taskId);
            }
        } catch (SchedulerException e) {
            log.warn("QuartzSchedulerService: failed to delete snapshot-detect taskId={}", taskId, e);
        }
    }

    // ── spec 030: drift-watch 周期校验调度 ────────────────────────────────────

    private static final String DRIFT_WATCH_GROUP = "drift-watch";

    /** 注册或更新 drift-watch 周期校验调度。 */
    public void scheduleDriftWatch(Long taskId, String cronExpression) throws SchedulerException {
        String quartzCron = normalizeCronExpression(cronExpression);
        JobKey jobKey = JobKey.jobKey(String.valueOf(taskId), DRIFT_WATCH_GROUP);
        TriggerKey triggerKey = TriggerKey.triggerKey(String.valueOf(taskId), DRIFT_WATCH_GROUP);

        JobDetail job = JobBuilder.newJob(DriftWatchQuartzJob.class)
                .withIdentity(jobKey)
                .usingJobData("taskId", taskId)
                .storeDurably()
                .build();

        CronTrigger trigger = TriggerBuilder.newTrigger()
                .withIdentity(triggerKey)
                .forJob(jobKey)
                .withSchedule(CronScheduleBuilder
                        .cronSchedule(quartzCron)
                        .inTimeZone(TimeZone.getTimeZone("Asia/Shanghai"))
                        .withMisfireHandlingInstructionDoNothing())
                .build();

        if (scheduler.checkExists(jobKey)) {
            scheduler.addJob(job, true);
            scheduler.rescheduleJob(triggerKey, trigger);
            log.info("QuartzSchedulerService: rescheduled drift-watch taskId={} cron={} (normalized: {})",
                    taskId, cronExpression, quartzCron);
        } else {
            scheduler.scheduleJob(job, trigger);
            log.info("QuartzSchedulerService: scheduled drift-watch taskId={} cron={} (normalized: {})",
                    taskId, cronExpression, quartzCron);
        }
    }

    /** 删除 drift-watch 调度。 */
    public void deleteDriftWatch(Long taskId) {
        try {
            JobKey jobKey = JobKey.jobKey(String.valueOf(taskId), DRIFT_WATCH_GROUP);
            if (scheduler.checkExists(jobKey)) {
                scheduler.deleteJob(jobKey);
                log.info("QuartzSchedulerService: deleted drift-watch taskId={}", taskId);
            }
        } catch (SchedulerException e) {
            log.warn("QuartzSchedulerService: failed to delete drift-watch taskId={}", taskId, e);
        }
    }

    /**
     * 删除 drift-watch group 中不再属于任何有效任务的 job/trigger。
     * <p>只接受业务层计算出的有效 taskId 集合，避免 Quartz service 自己猜测数据库状态。
     * 该操作幂等，适用于启动/定时巡检和一次性历史孤儿清理。
     */
    public int deleteOrphanDriftWatch(Set<Long> validTaskIds) throws SchedulerException {
        Set<Long> valid = validTaskIds == null ? Set.of() : Set.copyOf(validTaskIds);
        int deleted = 0;
        Set<JobKey> jobs = scheduler.getJobKeys(
                GroupMatcher.jobGroupEquals(DRIFT_WATCH_GROUP));
        for (JobKey jobKey : jobs) {
            Long taskId = parseTaskId(jobKey.getName());
            if (taskId == null || !valid.contains(taskId)) {
                if (scheduler.deleteJob(jobKey)) {
                    deleted++;
                }
            }
        }

        // 防御 JobDetail 已被删除但 trigger 残留的异常状态。
        Set<TriggerKey> triggers = scheduler.getTriggerKeys(
                GroupMatcher.triggerGroupEquals(DRIFT_WATCH_GROUP));
        for (TriggerKey triggerKey : triggers) {
            Long taskId = parseTaskId(triggerKey.getName());
            if (taskId == null || !valid.contains(taskId)) {
                if (scheduler.unscheduleJob(triggerKey)) {
                    deleted++;
                }
            }
        }
        if (deleted > 0) {
            log.info("QuartzSchedulerService: deleted {} orphan drift-watch job/trigger(s), validTaskIds={}",
                    deleted, valid.size());
        }
        return deleted;
    }

    private static Long parseTaskId(String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        try {
            return Long.valueOf(name);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
