package com.dfygt.dfetl.server.service;

import lombok.extern.slf4j.Slf4j;
import org.quartz.DisallowConcurrentExecution;
import org.quartz.Job;
import org.quartz.JobExecutionContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Spec 030：drift-watch 周期校验 Quartz Job。
 * <p>
 * JobKey group="drift-watch"，name=taskId。
 * 异常由 {@link DriftWatchService} 内部吞掉，避免触发 Quartz misfire。
 */
@Component
@DisallowConcurrentExecution
@Slf4j
public class DriftWatchQuartzJob implements Job {

    @Autowired
    private DriftWatchService driftWatchService;

    @Override
    public void execute(JobExecutionContext context) {
        Long taskId = context.getMergedJobDataMap().getLong("taskId");
        log.info("DriftWatchQuartzJob triggered: taskId={}", taskId);
        driftWatchService.runDriftCheck(taskId);
    }
}
