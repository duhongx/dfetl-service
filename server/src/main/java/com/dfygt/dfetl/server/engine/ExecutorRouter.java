package com.dfygt.dfetl.server.engine;

import com.dfygt.dfetl.server.entity.SyncTask;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 根据任务的 executorType 字段分发到对应的 ExecutorStrategy。
 * 当前仅保留 SeaTunnel Cluster 策略。
 */
@Service
@Slf4j
public class ExecutorRouter {

    private static final String DEFAULT_TYPE = "SEATUNNEL_CLUSTER";

    private final Map<String, ExecutorStrategy> strategies;

    public ExecutorRouter(List<ExecutorStrategy> all) {
        this.strategies = all.stream()
                .collect(Collectors.toMap(ExecutorStrategy::type, s -> s));
        log.info("ExecutorRouter: loaded strategies={}", this.strategies.keySet());
    }

    public ExecutorStrategy route(SyncTask task) {
        String configuredType = task.getExecutorType();
        String type = configuredType == null || configuredType.isBlank()
                ? DEFAULT_TYPE
                : configuredType.trim();
        ExecutorStrategy strategy = strategies.get(type);
        if (strategy == null) {
            log.error("ExecutorRouter: no strategy registered for executor_type={}; registered={}",
                    type, strategies.keySet());
            throw new IllegalStateException("No ExecutorStrategy registered for executor_type=" + type);
        }
        return strategy;
    }
}
