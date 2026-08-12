package com.dfygt.dfetl.server.service;

import lombok.extern.slf4j.Slf4j;
import org.quartz.DisallowConcurrentExecution;
import org.quartz.Job;
import org.quartz.JobExecutionContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Spec 020.2：自动 detect (+条件 apply) 的 Quartz Job。
 * <p>
 * JobKey group="snapshot-detect"，name=taskId（与业务 cron 任务在不同 group，避免冲突）。
 * 全部异常由 {@link SnapshotOrchestrator} 内部吞掉，不向 Quartz 抛出，避免触发 misfire。
 */
@Component
@DisallowConcurrentExecution
@Slf4j
public class SnapshotDetectQuartzJob implements Job {

    @Autowired
    private SnapshotOrchestrator snapshotOrchestrator;

    @Override
    public void execute(JobExecutionContext context) {
        Long taskId = context.getMergedJobDataMap().getLong("taskId");
        log.info("SnapshotDetectQuartzJob triggered: taskId={}", taskId);
        snapshotOrchestrator.runScheduledDetect(taskId);
    }
}
