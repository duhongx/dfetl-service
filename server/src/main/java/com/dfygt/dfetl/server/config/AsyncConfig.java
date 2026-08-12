package com.dfygt.dfetl.server.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * 异步线程池配置 — 消息发布使用独立线程池，与同步任务线程池隔离。
 */
@Configuration
@EnableAsync
public class AsyncConfig {

    /**
     * 消息发布专用线程池。
     * <ul>
     *   <li>核心线程数 2：日常增量发布并发不高</li>
     *   <li>最大线程数 4：应对全量发布突发</li>
     *   <li>队列容量 100：缓冲短时间内的多任务触发</li>
     *   <li>拒绝策略 CallerRunsPolicy：队列满时由调用线程执行，保证不丢失</li>
     * </ul>
     */
    @Bean("messagePublishExecutor")
    public Executor messagePublishExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(100);
        executor.setThreadNamePrefix("msg-publish-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.initialize();
        return executor;
    }

    /** 数据预检只允许有限并发，队列满时保留 PENDING 事实等待恢复器重试。 */
    @Bean("precheckExecutor")
    public Executor precheckExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(2);
        executor.setQueueCapacity(20);
        executor.setThreadNamePrefix("data-precheck-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        executor.initialize();
        return executor;
    }

    /** 预检问题文件导出独立串行执行，避免大文件导出挤占数据预检执行线程。 */
    @Bean("precheckExportExecutor")
    public Executor precheckExportExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(1);
        executor.setQueueCapacity(20);
        executor.setThreadNamePrefix("precheck-export-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        executor.initialize();
        return executor;
    }
}
