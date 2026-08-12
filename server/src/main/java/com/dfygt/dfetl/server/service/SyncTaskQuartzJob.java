package com.dfygt.dfetl.server.service;

import lombok.extern.slf4j.Slf4j;
import org.quartz.DisallowConcurrentExecution;
import org.quartz.Job;
import org.quartz.JobExecutionContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Quartz Job：只负责入队，不直接执行 ETL。
 * <p>
 * 实际执行由 TaskExecutionQueue → DfetlExecutorService 完成，
 * 与 Quartz 线程池解耦，避免 Quartz 线程被长时间占用。
 */
@Component
@DisallowConcurrentExecution  // 同一 JobKey 不并发触发
@Slf4j
public class SyncTaskQuartzJob implements Job {

    // 使用字段注入（Quartz 通过 SpringBeanJobFactory 注入）
    @Autowired
    private TaskExecutionQueue executionQueue;

    @Override
    public void execute(JobExecutionContext context) {
        Long taskId = context.getMergedJobDataMap().getLong("taskId");
        log.info("SyncTaskQuartzJob triggered: taskId={}", taskId);
        try {
            executionQueue.submit(taskId, "SCHEDULER");
        } catch (IllegalStateException e) {
            log.info("SyncTaskQuartzJob skipped duplicate trigger: taskId={} reason={}", taskId, e.getMessage());
        }
    }
}
