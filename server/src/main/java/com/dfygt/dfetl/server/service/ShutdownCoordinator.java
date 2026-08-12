package com.dfygt.dfetl.server.service;

import com.dfygt.dfetl.server.repository.TaskExecutionRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * 优雅停机协调器。
 * <p>
 * 实现 SmartLifecycle 接口，在 Spring 容器关闭时协调停机流程：
 * 1. 通知 TaskExecutionQueue 进入 drain 模式（停止接受新任务、中断等待线程）
 * 2. 等待在途任务完成（最多 gracefulTimeoutSeconds 秒）
 * 3. 超时则标记残留任务为 INTERRUPTED
 * <p>
 * phase 设为 Integer.MAX_VALUE - 1，确保在其他 SmartLifecycle bean 之前被停止。
 */
@Component
@Slf4j
public class ShutdownCoordinator implements SmartLifecycle {

    private final TaskExecutionQueue taskExecutionQueue;
    private final TaskExecutionRepository executionRepository;
    private volatile boolean running = false;

    @Value("${dfetl.shutdown.graceful-timeout-seconds:25}")
    private int gracefulTimeoutSeconds;

    public ShutdownCoordinator(TaskExecutionQueue taskExecutionQueue,
                               TaskExecutionRepository executionRepository) {
        this.taskExecutionQueue = taskExecutionQueue;
        this.executionRepository = executionRepository;
    }

    @Override
    public int getPhase() {
        return Integer.MAX_VALUE - 1;
    }

    @Override
    public void start() {
        this.running = true;
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public void stop() {
        stop(() -> {});
    }

    @Override
    public void stop(Runnable callback) {
        try {
            int inFlightCount = taskExecutionQueue.getActiveCount();
            log.info("ShutdownCoordinator: initiating graceful shutdown, inFlight={}, timeout={}s",
                    inFlightCount, gracefulTimeoutSeconds);

            taskExecutionQueue.initiateShutdown();

            boolean drained = taskExecutionQueue.awaitDrain(
                    gracefulTimeoutSeconds, TimeUnit.SECONDS);

            if (!drained) {
                Set<Long> remaining = taskExecutionQueue.getActiveTaskIds();
                log.warn("ShutdownCoordinator: drain timeout reached, marking {} tasks as INTERRUPTED: {}",
                        remaining.size(), remaining);
                markAsInterrupted(remaining);
            } else {
                log.info("ShutdownCoordinator: all in-flight tasks drained successfully");
            }
        } catch (Exception e) {
            log.error("ShutdownCoordinator: error during graceful shutdown: {}", e.getMessage(), e);
        } finally {
            this.running = false;
            callback.run();
        }
    }

    private void markAsInterrupted(Set<Long> taskIds) {
        if (taskIds == null || taskIds.isEmpty()) return;
        Instant now = Instant.now();
        for (Long taskId : taskIds) {
            try {
                executionRepository.findTopByTaskIdAndStatusOrderByIdDesc(taskId, "RUNNING")
                        .ifPresent(exec -> {
                            exec.setStatus("INTERRUPTED");
                            exec.setErrorMsg("Forced shutdown: task did not complete within graceful timeout ("
                                    + gracefulTimeoutSeconds + "s)");
                            exec.setFinishedAt(now);
                            executionRepository.save(exec);
                            log.info("ShutdownCoordinator: marked exec={} task={} as INTERRUPTED",
                                    exec.getId(), taskId);
                        });
            } catch (Exception e) {
                log.warn("ShutdownCoordinator: failed to mark task {} as INTERRUPTED: {}",
                        taskId, e.getMessage());
            }
        }
    }
}
