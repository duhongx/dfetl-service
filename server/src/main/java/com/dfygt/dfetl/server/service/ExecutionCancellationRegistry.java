package com.dfygt.dfetl.server.service;

import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * JVM 内执行取消信号。
 *
 * <p>数据库 execution 终态用于跨实例审计；该 registry 用于立即阻止本 JVM 的排队、
 * retry sleep 和成功后动作。多实例真实互斥仍由 PostgreSQL advisory lock 与远端 stop 保证。
 */
@Component
public class ExecutionCancellationRegistry {

    private final Set<Long> cancelledTaskIds = ConcurrentHashMap.newKeySet();
    private final Map<Long, Thread> runnerThreads = new ConcurrentHashMap<>();

    public void request(Long taskId) {
        if (taskId != null) {
            cancelledTaskIds.add(taskId);
            Thread runner = runnerThreads.get(taskId);
            if (runner != null) {
                runner.interrupt();
            }
        }
    }

    public boolean isRequested(Long taskId) {
        return taskId != null && cancelledTaskIds.contains(taskId);
    }

    public void clear(Long taskId) {
        if (taskId != null) {
            cancelledTaskIds.remove(taskId);
            runnerThreads.remove(taskId);
        }
    }

    public void registerRunner(Long taskId, Thread runner) {
        if (taskId != null && runner != null) {
            runnerThreads.put(taskId, runner);
        }
    }

    public void unregisterRunner(Long taskId, Thread runner) {
        if (taskId != null && runner != null) {
            runnerThreads.remove(taskId, runner);
        }
    }

    public void unregisterRunner(Long taskId) {
        if (taskId != null) {
            runnerThreads.remove(taskId);
        }
    }
}
