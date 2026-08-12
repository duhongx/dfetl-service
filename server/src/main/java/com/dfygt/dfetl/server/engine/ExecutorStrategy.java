package com.dfygt.dfetl.server.engine;

import com.dfygt.dfetl.server.entity.SyncTask;
import com.dfygt.dfetl.server.entity.TaskExecution;
import com.dfygt.dfetl.server.service.WatermarkService;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.concurrent.CompletableFuture;

/**
 * 执行引擎策略接口。
 * 每种引擎（SeaTunnel Local / SeaTunnel Cluster 等）实现该接口。
 */
public interface ExecutorStrategy {

    /**
     * 引擎类型标识，与 sync_task.executor_type 字段对应，如 "SEATUNNEL_CLUSTER"。
     */
    String type();

    /**
     * 异步执行 ETL 任务。
     */
    CompletableFuture<ExecutionResult> execute(
            SyncTask task,
            WatermarkService.WindowContext window,
            TaskExecution exec
    );

    /**
     * 流式推送引擎日志（SSE）。
     */
    void streamLogs(SyncTask task, SseEmitter emitter);
}
