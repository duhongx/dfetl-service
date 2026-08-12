package com.dfygt.dfetl.server.engine.seatunnel;

import com.dfygt.dfetl.server.common.ExecutionErrorSanitizer;
import com.dfygt.dfetl.server.entity.TaskExecution;
import com.dfygt.dfetl.server.engine.ExecutionResult;
import com.dfygt.dfetl.server.repository.TaskExecutionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * SeaTunnel 生命周期回写探针（spec 015c）。
 *
 * <p>由 {@link SeaTunnelExecutorStrategy} 在拿到 jobId 后启动；周期性轮询
 * {@link SeaTunnelRestClient#getJobInfo(String)}，把 status / readRows / writeRows / errorMsg 写回
 * {@code task_execution}。轮询直到达到终态或 {@code probe.maxPollMinutes} 超时。
 *
 * <p>本探针**只回写指标**，不影响 ExecutorStrategy 主流程的 ExecutionResult 返回。
 * 在主流程已写入终态后，探针会跳过再次写入。
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "dfetl.executor.seatunnel", name = "enabled", havingValue = "true")
public class SeaTunnelLifecycleProbe {

    private final SeaTunnelRestClient restClient;
    private final TaskExecutionRepository executionRepo;
    private final SeaTunnelProperties props;

    private final Executor probeExecutor = Executors.newVirtualThreadPerTaskExecutor();

    /**
     * 异步开始追踪一个 jobId，回写 task_execution。
     *
     * @return 终态后的 {@link SeaTunnelRestClient.JobInfo}（封装在 future 中）；超时则返回最后一次成功结果
     */
    public CompletableFuture<Optional<SeaTunnelRestClient.JobInfo>> trackAsync(Long executionId, String jobId) {
        return CompletableFuture.supplyAsync(() -> trackBlocking(executionId, jobId), probeExecutor);
    }

    /** 同步轮询直到终态/超时；包内可见用于测试。 */
    Optional<SeaTunnelRestClient.JobInfo> trackBlocking(Long executionId, String jobId) {
        if (jobId == null || jobId.isBlank()) return Optional.empty();
        Instant deadline = Instant.now().plus(Duration.ofMinutes(props.probe().maxPollMinutes()));
        long intervalMs  = props.probe().intervalMs();
        Optional<SeaTunnelRestClient.JobInfo> last = Optional.empty();
        while (Instant.now().isBefore(deadline)) {
            Optional<SeaTunnelRestClient.JobInfo> info = restClient.getJobInfo(jobId);
            if (info.isPresent()) {
                last = info;
                applyToExecution(executionId, info.get());
                // 每次轮询打印 SeaTunnel 实时指标
                log.info("SeaTunnelLifecycleProbe: [poll] exec={} jobId={} status={} read={} write={} errMsg={}",
                        executionId, jobId,
                        info.get().mappedStatus(),
                        info.get().readRows(),
                        info.get().writeRows(),
                        compact(ExecutionErrorSanitizer.sanitize(info.get().errorMsg()), 200));
                if (info.get().isTerminal()) {
                    log.info("SeaTunnelLifecycleProbe: terminal status={} exec={} jobId={}",
                            info.get().mappedStatus(), executionId, jobId);
                    return info;
                }
            }
            try {
                TimeUnit.MILLISECONDS.sleep(intervalMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return last;
            }
        }
        log.warn("SeaTunnelLifecycleProbe: poll timeout exec={} jobId={} maxMinutes={}",
                executionId, jobId, props.probe().maxPollMinutes());
        return last;
    }

    /** 从 Java 堆栈中提取 Doris _load_error_log URL，使错误信息更可读 */
    private static final Pattern DORIS_ERROR_URL = Pattern.compile(
            "http://[\\w.:/]+/api/_load_error_log\\?file=[^\\s\"']+");
    private static final int ERROR_MSG_MAX = 4000;

    String formatErrorMessage(String raw) {
        if (raw == null || raw.isBlank()) return raw;

        raw = ExecutionErrorSanitizer.sanitize(raw);

        Matcher matcher = DORIS_ERROR_URL.matcher(raw);
        if (matcher.find()) {
            String errorUrl = matcher.group();
            String detail = fetchDorisErrorDetail(errorUrl);
            StringBuilder display = new StringBuilder();
            display.append("Doris Stream Load 写入失败：DATA_QUALITY_ERROR（过滤行超过阈值）");

            if (detail != null && !detail.isBlank()) {
                display.append('\n').append(detail);
            } else {
                String core = raw.lines()
                        .filter(l -> l.contains("DATA_QUALITY_ERROR") || l.contains("stream load error"))
                        .findFirst()
                        .map(String::trim)
                        .orElse("SeaTunnel Doris connector stream load error");
                display.append('\n').append(compact(core, 800));
            }
            display.append('\n').append("Load Error URL: ").append(errorUrl);
            return compact(ExecutionErrorSanitizer.sanitize(display.toString()), ERROR_MSG_MAX);
        }

        return compact(ExecutionErrorSanitizer.sanitize(raw), ERROR_MSG_MAX);
    }

    /**
     * 直接调用 Doris _load_error_log 接口，提取真实的字段错误原因。
     * 返回 null 表示拉取失败（网络不通或响应非 200），调用方应降级为显示 URL。
     */
    private String fetchDorisErrorDetail(String errorUrl) {
        try {
            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(5))
                    .build();
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(errorUrl))
                    .timeout(Duration.ofSeconds(8))
                    .GET()
                    .build();
            HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) return null;
            String body = resp.body();
            if (body == null || body.isBlank()) return null;

            List<String> reasons = body.lines()
                    .filter(l -> l.contains("Reason:") || l.contains("reason:"))
                    .map(l -> {
                        int idx = l.lastIndexOf("Reason:");
                        if (idx < 0) idx = l.lastIndexOf("reason:");
                        String reason = l.substring(idx).trim();
                        int srcLine = reason.indexOf(" src line ");
                        if (srcLine > 0) reason = reason.substring(0, srcLine).trim();
                        return reason;
                    })
                    .distinct()
                    .collect(Collectors.toList());

            if (!reasons.isEmpty()) {
                StringBuilder detail = new StringBuilder();
                detail.append("Doris 返回原因: ").append(String.join(" | ", reasons.stream().limit(3).toList()));
                return ExecutionErrorSanitizer.sanitize(detail.toString());
            }
            // 回退：取前 5 行非空内容
            return ExecutionErrorSanitizer.sanitize(body.lines()
                    .filter(l -> !l.isBlank())
                    .limit(5)
                    .collect(Collectors.joining(" | ")));
        } catch (Exception e) {
            log.debug("fetchDorisErrorDetail failed url={} err={}", errorUrl, e.getMessage());
            return null;
        }
    }

    private static String compact(String s, int max) {
        if (s == null || s.length() <= max) return s;
        return s.substring(0, max);
    }

    /**
     * 把 JobInfo 字段安全写回 task_execution。
     * <ul>
     *   <li>不覆盖已写入的终态（终态优先级：RECONCILE_REQUIRED&gt;FAILED&gt;CANCELLED&gt;SUCCESS&gt;RUNNING）</li>
     *   <li>轮询期只回写 engineReadRows / engineWriteRows；业务行数由终态同口径计数回写</li>
     *   <li>errorMsg 长度 cap 在 4000，避免写爆 TEXT 列被截断</li>
     * </ul>
     */

    void applyToExecution(Long executionId, SeaTunnelRestClient.JobInfo info) {
        executionRepo.findById(executionId).ifPresent(exec -> {
            // 如果主流程已写入最终状态，probe 不再覆盖
            String currentStatus = exec.getStatus();
            if ("SUCCESS".equals(currentStatus)
                    || ExecutionResult.STATUS_SUCCESS_WITH_DIRTY_ROWS.equals(currentStatus)
                    || "FAILED".equals(currentStatus)
                    || "RECONCILE_REQUIRED".equals(currentStatus)) {
                return;
            }

            boolean dirty = false;
            String mapped = info.mappedStatus();
            if ("SUCCESS".equals(mapped)
                    && exec.getExcludedRows() != null
                    && exec.getExcludedRows() > 0) {
                mapped = ExecutionResult.STATUS_SUCCESS_WITH_DIRTY_ROWS;
            }
            if (mapped != null && !shouldKeepExistingStatus(exec.getStatus(), mapped)) {
                exec.setStatus(mapped);
                dirty = true;
            }
            if (info.readRows() > 0 && (exec.getEngineReadRows() == null || info.readRows() > exec.getEngineReadRows())) {
                exec.setEngineReadRows(info.readRows());
                dirty = true;
            }
            if (info.writeRows() > 0 && (exec.getEngineWriteRows() == null || info.writeRows() > exec.getEngineWriteRows())) {
                exec.setEngineWriteRows(info.writeRows());
                dirty = true;
            }
            if (info.errorMsg() != null && !info.errorMsg().isBlank()) {
                String display = formatErrorMessage(info.errorMsg());
                if (!display.equals(exec.getErrorMsg())) {
                    exec.setErrorMsg(display);
                    dirty = true;
                }
            }
            if (dirty) executionRepo.save(exec);
        });
    }

    /**
     * 终态保护：已写入 SUCCESS/FAILED/CANCELLED/RECONCILE_REQUIRED 时，新拉到的非终态状态不应覆盖。
     */
    static boolean shouldKeepExistingStatus(String current, String incoming) {
        if (current == null) return false;
        boolean curTerminal = isTerminalStatus(current);
        boolean inTerminal  = isTerminalStatus(incoming);
        // RECONCILE_REQUIRED 表示平台已无法确认 SeaTunnel job 真实终态，不能被后续异步轮询覆盖。
        if (ExecutionResult.STATUS_RECONCILE_REQUIRED.equals(current)) return true;
        // 问题行终态包含 DFETL 业务分流事实，SeaTunnel 只知道引擎成功，不能把它降级成普通 SUCCESS。
        if (ExecutionResult.STATUS_SUCCESS_WITH_DIRTY_ROWS.equals(current)) return true;
        if (curTerminal && !inTerminal) return true;
        // FAILED/CANCELLED 不应被 SUCCESS 覆盖（防止 REST 滞后）
        if ("FAILED".equals(current)    && "SUCCESS".equals(incoming))   return true;
        if ("CANCELLED".equals(current) && "SUCCESS".equals(incoming))   return true;
        return false;
    }

    private static boolean isTerminalStatus(String s) {
        return "SUCCESS".equals(s)
                || ExecutionResult.STATUS_SUCCESS_WITH_DIRTY_ROWS.equals(s)
                || "FAILED".equals(s)
                || "CANCELLED".equals(s)
                || ExecutionResult.STATUS_RECONCILE_REQUIRED.equals(s);
    }

    /**
     * SSE：把 JobInfo 周期性推送到前端。失败 / 终态后自动 complete。
     *
     * <p>客户端断开连接时（IOException: broken pipe）静默退出，不向上抛出，
     * 避免触发 Tomcat NIO 线程的 JUL 日志路径，防止 logback ThrowableProxy 类加载器隔离问题。
     */
    public void streamProgress(Long executionId, String jobId, SseEmitter emitter) {
        probeExecutor.execute(() -> {
            try {
                if (jobId == null || jobId.isBlank()) {
                    trySend(emitter, SseEmitter.event().name("error").data("missing jobId"));
                    tryComplete(emitter);
                    return;
                }
                Instant deadline = Instant.now().plus(Duration.ofMinutes(props.probe().maxPollMinutes()));
                long intervalMs  = props.probe().intervalMs();
                while (Instant.now().isBefore(deadline)) {
                    Optional<SeaTunnelRestClient.JobInfo> info = restClient.getJobInfo(jobId);
                    if (info.isPresent()) {
                        SeaTunnelRestClient.JobInfo safeInfo = sanitizeJobInfo(info.get());
                        if (!trySend(emitter, SseEmitter.event().name("progress").data(safeInfo))) return;
                        applyToExecution(executionId, info.get());
                        if (info.get().isTerminal()) {
                            trySend(emitter, SseEmitter.event().name("done").data(info.get().mappedStatus()));
                            tryComplete(emitter);
                            return;
                        }
                    } else {
                        if (!trySend(emitter, SseEmitter.event().name("waiting").data("no jobInfo yet"))) return;
                    }
                    TimeUnit.MILLISECONDS.sleep(intervalMs);
                }
                trySend(emitter, SseEmitter.event().name("timeout").data("max poll minutes reached"));
                tryComplete(emitter);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                tryComplete(emitter);
            } catch (Exception e) {
                // 兜底：任何未预期异常都静默处理，不让异常逃逸到 Tomcat NIO 线程
                log.debug("streamProgress unexpected error exec={} jobId={}: {}", executionId, jobId,
                        ExecutionErrorSanitizer.sanitize(e.getMessage()));
                tryComplete(emitter);
            }
        });
    }

    private SeaTunnelRestClient.JobInfo sanitizeJobInfo(SeaTunnelRestClient.JobInfo info) {
        return new SeaTunnelRestClient.JobInfo(
                info.jobId(), info.jobName(), info.jobStatus(), info.mappedStatus(),
                info.createTime(), info.finishTime(), info.readRows(), info.writeRows(),
                formatErrorMessage(info.errorMsg()));
    }

    /**
     * 安全发送 SSE 事件；客户端已断开时（IOException）返回 false，调用方应退出轮询。
     */
    private boolean trySend(SseEmitter emitter, SseEmitter.SseEventBuilder event) {
        try {
            emitter.send(event);
            return true;
        } catch (IOException e) {
            // 客户端断开连接属于正常行为，静默退出
            log.debug("SSE client disconnected: {}", e.getMessage());
            return false;
        }
    }

    /**
     * 安全完成 SSE emitter；已断开时静默忽略。
     */
    private void tryComplete(SseEmitter emitter) {
        try {
            emitter.complete();
        } catch (Exception e) {
            log.debug("SSE complete ignored: {}", e.getMessage());
        }
    }
}
