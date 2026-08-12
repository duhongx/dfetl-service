package com.dfygt.dfetl.server.engine.seatunnel;

import com.dfygt.dfetl.server.config.retry.NonRetryableException;
import com.dfygt.dfetl.server.config.retry.RetryableException;
import com.dfygt.dfetl.server.config.retry.SeaTunnelCircuitBreaker;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * SeaTunnel Zeta REST client（spec 015c）。
 *
 * <p>SeaTunnel Zeta 使用内置 HTTP 服务器（seatunnel.engine.http），默认路径为：
 * <pre>
 *   GET {restBaseUrl}/job-info/{jobId}   ← restBaseUrl 已含 context-path，如 http://host:8080/seatunnel
 *   GET {restBaseUrl}/overview           ← 健康探测（返回集群 worker/slot 统计）
 * </pre>
 *
 * <p>jobId 由 {@link SeaTunnelExecutorStrategy} 提交后从 stdout 捕获。
 * 本客户端做最小封装：拉到原始 JSON → 解析到 {@link JobInfo}。
 *
 * <p>失败语义：网络异常/HTTP 非 2xx/JSON 解析失败 → 返回 {@code Optional.empty()}，
 * 由 {@link SeaTunnelLifecycleProbe} 决定是否重试。
 *
 * <p>集成重试与断路器：所有 HTTP 调用通过 RetryTemplate 包装，并在调用前检查断路器状态。
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "dfetl.executor.seatunnel", name = "enabled", havingValue = "true")
public class SeaTunnelRestClient {

    private final SeaTunnelProperties props;
    private final ObjectMapper objectMapper;
    private final SeaTunnelCircuitBreaker circuitBreaker;
    private final RetryTemplate submitRetryTemplate;
    private final RetryTemplate queryRetryTemplate;
    private final RetryTemplate stopRetryTemplate;

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    public SeaTunnelRestClient(
            SeaTunnelProperties props,
            ObjectMapper objectMapper,
            SeaTunnelCircuitBreaker circuitBreaker,
            @Qualifier("seaTunnelSubmitRetryTemplate") RetryTemplate submitRetryTemplate,
            @Qualifier("seaTunnelQueryRetryTemplate") RetryTemplate queryRetryTemplate,
            @Qualifier("seaTunnelStopRetryTemplate") RetryTemplate stopRetryTemplate) {
        this.props = props;
        this.objectMapper = objectMapper;
        this.circuitBreaker = circuitBreaker;
        this.submitRetryTemplate = submitRetryTemplate;
        this.queryRetryTemplate = queryRetryTemplate;
        this.stopRetryTemplate = stopRetryTemplate;
    }

    /**
     * 简化后的 Job 信息。所有字段都可能为 null（未启动 / 尚未上报指标）。
     *
     * @param jobId      请求时透传
     * @param jobName    SeaTunnel 内部 job 名（可能与提交时 -n 不一致）
     * @param jobStatus  RUNNING / FINISHED / CANCELED / FAILED / SCHEDULED 等（透传）
     * @param mappedStatus 映射后的 task_execution.status：RUNNING / SUCCESS / CANCELLED / FAILED / null
     * @param createTime ms 时间戳，可能为 0
     * @param finishTime ms 时间戳，可能为 0
     * @param readRows   累计读取行数（聚合所有 vertex）
     * @param writeRows  累计写入行数（聚合所有 vertex）
     * @param errorMsg   作业错误（FAILED 时有值）
     */
    public record JobInfo(
            String jobId,
            String jobName,
            String jobStatus,
            String mappedStatus,
            long createTime,
            long finishTime,
            long readRows,
            long writeRows,
            String errorMsg
    ) {
        public boolean isTerminal() {
            return "SUCCESS".equals(mappedStatus)
                    || "FAILED".equals(mappedStatus)
                    || "CANCELLED".equals(mappedStatus);
        }
    }

    /**
     * 拉取一次 job-info。返回 {@code empty} 表示 jobId 未知或本次拉取失败（并非 job 失败）。
     * 集成断路器 + 重试：断路器 OPEN 时直接返回 empty，否则通过 RetryTemplate 包装 HTTP 调用。
     */
    public Optional<JobInfo> getJobInfo(String jobId) {
        if (jobId == null || jobId.isBlank()) return Optional.empty();

        if (!circuitBreaker.allowRequest()) {
            log.warn("[Retry:SeaTunnel:getJobInfo] circuit breaker OPEN, fast-fail for jobId={}", jobId);
            return Optional.empty();
        }

        try {
            return queryRetryTemplate.execute(context -> {
                try {
                    Optional<JobInfo> result = doGetJobInfo(jobId);
                    if (result.isPresent()) {
                        circuitBreaker.recordSuccess();
                    } else {
                        // empty 可能是 jobId 不存在或 HTTP 非 2xx，作为可重试失败传播；
                        // 失败计数只在重试耗尽后由最外层 catch 统一记录一次，避免被重试放大。
                        throw new RetryableException("getJobInfo returned empty for jobId=" + jobId);
                    }
                    return result;
                } catch (RetryableException e) {
                    throw e;
                } catch (NonRetryableException e) {
                    throw e;
                } catch (Exception e) {
                    classifyAndThrow(e);
                    return Optional.empty(); // unreachable
                }
            });
        } catch (RetryableException e) {
            // 重试耗尽：本次 getJobInfo 调用整体失败，记录一次失败计数
            circuitBreaker.recordFailure();
            return Optional.empty();
        } catch (NonRetryableException e) {
            // 4xx 客户端错误（如 jobId 不存在）不代表集群不可用，不计入熔断失败
            return Optional.empty();
        } catch (Exception e) {
            circuitBreaker.recordFailure();
            return Optional.empty();
        }
    }

    /**
     * REST 提交结果：成功时 {@code jobId != null}，失败时 {@code errorMsg} 含 SeaTunnel 返回的原始错误。
     */
    public record SubmitResult(String jobId, String errorMsg) {
        public boolean success() { return jobId != null && !jobId.isBlank(); }
        /** 快捷构造成功结果 */
        public static SubmitResult ok(String jobId)   { return new SubmitResult(jobId, null); }
        /** 快捷构造失败结果 */
        public static SubmitResult fail(String msg)   { return new SubmitResult(null, msg); }
    }

    /**
     * stop-job 请求结果。{@link #success()} 只表示 REST 请求被 SeaTunnel 接收且返回了 jobId，
     * 不代表该 job 已真实停止；调用方必须继续查询 job-info 确认终态。
     */
    public record StopResult(String jobId, String errorMsg) {
        public boolean success() { return jobId != null && !jobId.isBlank(); }
        public static StopResult ok(String jobId) { return new StopResult(jobId, null); }
        public static StopResult fail(String msg) { return new StopResult(null, msg); }
    }

    /**
     * 通过 REST API 提交 SeaTunnel 作业（cluster 模式，无需本地安装 seatunnel.sh）。
     *
     * <p>POST {restBaseUrl}/submit-job，body 为 JSON（env / source / transform / sink）。
     * SeaTunnel 2.3.x 响应：{@code {"jobId":"843788663310905344","jobName":"..."}}
     *
     * <p>集成断路器 + 重试：断路器 OPEN 时直接返回失败，否则通过 RetryTemplate 包装 HTTP 调用。
     *
     * @param jobConfig 作业配置（env/source/sink 的 Map 结构，序列化为 JSON POST body）
     * @return {@link SubmitResult}；{@code success()==true} 时含 jobId，否则含 errorMsg
     */
    public SubmitResult submitJob(Map<String, Object> jobConfig) {
        if (!circuitBreaker.allowRequest()) {
            log.warn("[Retry:SeaTunnel:submitJob] circuit breaker OPEN, fast-fail");
            return SubmitResult.fail("Circuit breaker OPEN: SeaTunnel cluster unavailable");
        }

        try {
            return submitRetryTemplate.execute(context -> {
                try {
                    SubmitResult result = doSubmitJob(jobConfig);
                    if (result.success()) {
                        circuitBreaker.recordSuccess();
                    } else {
                        // 失败计数只在重试耗尽后由最外层 catch 统一记录一次，避免被重试放大。
                        throw new RetryableException("submitJob failed: " + result.errorMsg());
                    }
                    return result;
                } catch (RetryableException e) {
                    throw e;
                } catch (NonRetryableException e) {
                    throw e;
                } catch (Exception e) {
                    classifyAndThrow(e);
                    return null; // unreachable
                }
            });
        } catch (RetryableException e) {
            // 重试耗尽：本次 submitJob 调用整体失败，记录一次失败计数
            circuitBreaker.recordFailure();
            return SubmitResult.fail(e.getMessage());
        } catch (NonRetryableException e) {
            // 4xx 客户端错误不代表集群不可用，不计入熔断失败
            return SubmitResult.fail(e.getMessage());
        } catch (Exception e) {
            circuitBreaker.recordFailure();
            return SubmitResult.fail(e.getMessage());
        }
    }

    /**
     * 尝试停止已提交的 SeaTunnel 作业。
     *
     * <p>用于平台探测超时或作业长时间停留非终态时收口风险：如果平台已经不能确认作业成功，
     * 则应尽量阻止真实 job 后续继续写入 Doris，避免"平台失败但目标端继续变化"的状态漂移。
     *
     * <p>集成断路器 + 重试：断路器 OPEN 时直接返回失败，否则通过 RetryTemplate 包装 HTTP 调用。
     */
    public StopResult stopJob(String jobId, boolean force) {
        if (jobId == null || jobId.isBlank()) {
            return StopResult.fail("missing jobId");
        }

        if (!circuitBreaker.allowRequest()) {
            log.warn("[Retry:SeaTunnel:stopJob] circuit breaker OPEN, fast-fail for jobId={}", jobId);
            return StopResult.fail("Circuit breaker OPEN: SeaTunnel cluster unavailable");
        }

        try {
            return stopRetryTemplate.execute(context -> {
                try {
                    StopResult result = doStopJob(jobId, force);
                    if (result.success()) {
                        circuitBreaker.recordSuccess();
                    } else {
                        // 失败计数只在重试耗尽后由最外层 catch 统一记录一次，避免被重试放大。
                        throw new RetryableException("stopJob failed: " + result.errorMsg());
                    }
                    return result;
                } catch (RetryableException e) {
                    throw e;
                } catch (NonRetryableException e) {
                    throw e;
                } catch (Exception e) {
                    classifyAndThrow(e);
                    return null; // unreachable
                }
            });
        } catch (RetryableException e) {
            // 重试耗尽：本次 stopJob 调用整体失败，记录一次失败计数
            circuitBreaker.recordFailure();
            return StopResult.fail(e.getMessage());
        } catch (NonRetryableException e) {
            // 4xx 客户端错误不代表集群不可用，不计入熔断失败
            return StopResult.fail(e.getMessage());
        } catch (Exception e) {
            circuitBreaker.recordFailure();
            return StopResult.fail(e.getMessage());
        }
    }

    /** 集群健康检测：GET /overview 返回 200 且 workers > 0 即认为 alive。 */
    public boolean isClusterAlive() {
        String base = stripTrailingSlash(props.cluster().restBaseUrl());
        HttpRequest req = HttpRequest.newBuilder(URI.create(base + "/overview"))
                .GET().timeout(Duration.ofSeconds(3)).build();
        try {
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            return resp.statusCode() / 100 == 2;
        } catch (Exception e) {
            return false;
        }
    }

    // ── 内部 HTTP 调用方法（被 RetryTemplate 包装） ─────────────────────────

    /**
     * 实际执行 getJobInfo HTTP 调用的内部方法。
     */
    private Optional<JobInfo> doGetJobInfo(String jobId) {
        String base = stripTrailingSlash(props.cluster().restBaseUrl());
        URI uri = URI.create(base + "/job-info/" + jobId);
        HttpRequest req = HttpRequest.newBuilder(uri)
                .GET()
                .timeout(Duration.ofSeconds(5))
                .header("Accept", "application/json")
                .build();
        try {
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            int statusCode = resp.statusCode();
            if (statusCode / 100 == 2) {
                return Optional.of(parseJobInfo(jobId, resp.body()));
            }
            // 分类 HTTP 错误码
            classifyHttpStatus(statusCode, resp.body(), "getJobInfo");
            return Optional.empty(); // unreachable
        } catch (IOException e) {
            throw new RetryableException("Transient IO error: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RetryableException("Interrupted: " + e.getMessage(), e);
        }
    }

    /**
     * 实际执行 submitJob HTTP 调用的内部方法。
     */
    private SubmitResult doSubmitJob(Map<String, Object> jobConfig) {
        String base = stripTrailingSlash(props.cluster().restBaseUrl());
        String url = base + "/submit-job";
        try {
            String bodyJson = objectMapper.writeValueAsString(jobConfig);
            log.info("SeaTunnelRestClient.submitJob → POST {} body.len={}", url, bodyJson.length());

            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(bodyJson))
                    .timeout(Duration.ofSeconds(30))
                    .build();

            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            int statusCode = resp.statusCode();
            if (statusCode / 100 != 2) {
                String body = resp.body().length() > 500 ? resp.body().substring(0, 500) : resp.body();
                log.warn("SeaTunnelRestClient.submitJob HTTP {}: {}", statusCode, body);
                // 分类 HTTP 错误码并抛出对应异常
                classifyHttpStatus(statusCode, resp.body(), "submitJob");
                return null; // unreachable
            }
            JsonNode node = objectMapper.readTree(resp.body());
            String jobId = firstNonBlank(pathText(node, "jobId"), pathText(node, "id"));
            if (jobId == null) {
                log.warn("SeaTunnelRestClient.submitJob: response missing jobId: {}", resp.body());
                return SubmitResult.fail("SeaTunnel REST 响应缺少 jobId 字段: " + resp.body());
            }
            log.info("SeaTunnelRestClient.submitJob: submitted jobId={}", jobId);
            return SubmitResult.ok(jobId);
        } catch (RetryableException | NonRetryableException e) {
            throw e; // 已分类，直接传播
        } catch (IOException e) {
            throw new RetryableException("Transient IO error: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RetryableException("Interrupted: " + e.getMessage(), e);
        }
    }

    /**
     * 实际执行 stopJob HTTP 调用的内部方法。
     */
    private StopResult doStopJob(String jobId, boolean force) {
        String base = stripTrailingSlash(props.cluster().restBaseUrl());
        String url = base + "/stop-job";
        try {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("jobId", parseJobId(jobId));
            body.put("isStopWithSavePoint", false);
            body.put("force", force);
            String bodyJson = objectMapper.writeValueAsString(body);
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(bodyJson))
                    .timeout(Duration.ofSeconds(10))
                    .build();

            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            int statusCode = resp.statusCode();
            if (statusCode / 100 != 2) {
                String response = resp.body() == null ? "" : resp.body();
                String clipped = response.length() > 500 ? response.substring(0, 500) : response;
                log.warn("SeaTunnelRestClient.stopJob HTTP {} jobId={} body={}",
                        statusCode, jobId, clipped);
                // 分类 HTTP 错误码并抛出对应异常
                classifyHttpStatus(statusCode, resp.body(), "stopJob");
                return null; // unreachable
            }
            JsonNode node = objectMapper.readTree(resp.body());
            String returnedJobId = firstNonBlank(pathText(node, "jobId"), pathText(node, "id"), jobId);
            log.info("SeaTunnelRestClient.stopJob: stop requested jobId={} force={}", returnedJobId, force);
            return StopResult.ok(returnedJobId);
        } catch (RetryableException | NonRetryableException e) {
            throw e; // 已分类，直接传播
        } catch (IOException e) {
            throw new RetryableException("Transient IO error: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RetryableException("Interrupted: " + e.getMessage(), e);
        }
    }

    // ── 异常分类 ─────────────────────────────────────────────────────────────

    /**
     * 根据 HTTP 状态码分类并抛出对应异常。
     * 5xx / 408 / 429 → RetryableException；4xx（非 408/429）→ NonRetryableException
     */
    private void classifyHttpStatus(int statusCode, String responseBody, String operation) {
        String errMsg = extractMessage(responseBody,
                responseBody != null && responseBody.length() > 500
                        ? responseBody.substring(0, 500) : responseBody);

        if (statusCode / 100 == 5) {
            throw new RetryableException(
                    "Server error HTTP " + statusCode + " in " + operation + ": " + errMsg);
        }
        if (statusCode == 408 || statusCode == 429) {
            throw new RetryableException(
                    "Retryable client error HTTP " + statusCode + " in " + operation + ": " + errMsg);
        }
        // 4xx (non-408/429) → non-retryable
        throw new NonRetryableException(
                "Client error HTTP " + statusCode + " in " + operation + ": " + errMsg);
    }

    /**
     * 对通用异常进行分类并抛出 RetryableException 或 NonRetryableException。
     * 用于捕获 RetryTemplate 回调中的未预期异常。
     */
    private void classifyAndThrow(Exception e) {
        if (e instanceof IOException || e instanceof java.net.ConnectException) {
            throw new RetryableException("Transient IO error: " + e.getMessage(), e);
        }
        if (e instanceof HttpServerErrorException) {
            throw new RetryableException("Server error: " + e.getMessage(), e);
        }
        if (e instanceof HttpClientErrorException clientErr) {
            int code = clientErr.getStatusCode().value();
            if (code == 408 || code == 429) {
                throw new RetryableException("Retryable client error " + code + ": " + e.getMessage(), e);
            }
            throw new NonRetryableException("Client error " + code + ": " + e.getMessage(), e);
        }
        if (e instanceof ResourceAccessException) {
            throw new RetryableException("Resource access error: " + e.getMessage(), e);
        }
        // Default: treat as retryable (network issues)
        throw new RetryableException("Unknown error: " + e.getMessage(), e);
    }

    // ── 辅助方法 ─────────────────────────────────────────────────────────────

    private String extractMessage(String body, String fallback) {
        try {
            JsonNode n = objectMapper.readTree(body);
            String msg = firstNonBlank(pathText(n, "message"), pathText(n, "msg"), pathText(n, "error"));
            return msg != null ? msg : fallback;
        } catch (IOException e) {
            return fallback;
        }
    }

    private static Object parseJobId(String jobId) {
        String normalized = jobId.trim();
        try {
            return Long.parseLong(normalized);
        } catch (NumberFormatException ignored) {
            return normalized;
        }
    }

    // ── parsing ──────────────────────────────────────────────────────────────

    /**
     * 解析 SeaTunnel job-info 的 JSON。
     *
     * <p>SeaTunnel 在不同小版本中字段路径略有差异，本方法做尽量宽容的解析：
     * <ul>
     *   <li>jobStatus / status / state 三选一</li>
     *   <li>metrics 既支持 {@code metrics.SourceReceivedCount} 顶层 key，也支持
     *       {@code metrics.{vertexId}.SourceReceivedCount} 嵌套形式（按 vertex 聚合）</li>
     *   <li>errorMessage / errorMsg / failedMessage 三选一</li>
     * </ul>
     */
    JobInfo parseJobInfo(String jobId, String body) {
        JsonNode root;
        try {
            root = objectMapper.readTree(body);
        } catch (IOException e) {
            log.debug("SeaTunnelRestClient: parse JSON failed jobId={} error={}", jobId, e.getMessage());
            return emptyInfo(jobId);
        }
        String status = firstNonBlank(
                pathText(root, "jobStatus"),
                pathText(root, "status"),
                pathText(root, "state"));
        String mapped = mapStatus(status);
        String name   = firstNonBlank(pathText(root, "jobName"), pathText(root, "name"));
        long create   = pathLong(root, "createTime");
        long finish   = pathLong(root, "finishTime");
        String err    = firstNonBlank(
                pathText(root, "errorMessage"),
                pathText(root, "errorMsg"),
                pathText(root, "failedMessage"));

        long readRows  = sumMetric(root.path("metrics"), "SourceReceivedCount");
        long writeRows = sumMetric(root.path("metrics"), "SinkWriteCount");

        return new JobInfo(jobId, name, status, mapped, create, finish, readRows, writeRows, err);
    }

    /**
     * 把 SeaTunnel 状态映射到 task_execution.status 的取值集合。
     */
    static String mapStatus(String raw) {
        if (raw == null) return null;
        String s = raw.toUpperCase();
        return switch (s) {
            case "RUNNING", "DOING", "EXECUTING"        -> "RUNNING";
            case "SCHEDULED", "PENDING", "INITIALIZING",
                 "SUBMITTED", "STARTING", "CREATED"    -> "SCHEDULED";
            case "FINISHED", "SUCCESS", "SUCCEEDED"     -> "SUCCESS";
            case "CANCELED", "CANCELLED", "CANCELING"   -> "CANCELLED";
            case "FAILED", "FAILURE", "ERROR", "FAILING",
                 "STOPPED", "KILLED"                    -> "FAILED";
            default -> s;
        };
    }

    private long sumMetric(JsonNode metrics, String key) {
        if (metrics == null || metrics.isMissingNode() || metrics.isNull()) return 0L;
        // Form A: metrics.SourceReceivedCount = 123  (number)
        // Form A': metrics.SourceReceivedCount = "123" (string — SeaTunnel 2.3.x returns strings)
        JsonNode flat = metrics.path(key);
        if (flat.isNumber()) return flat.asLong();
        if (flat.isTextual()) {
            try { return Long.parseLong(flat.asText().trim()); } catch (NumberFormatException ignored) {}
        }
        if (flat.isObject()) {
            // metrics.SourceReceivedCount = { "vertex1": 100, "vertex2": 23 }
            long total = 0;
            for (var it = flat.fields(); it.hasNext(); ) {
                Map.Entry<String, JsonNode> e = it.next();
                if (e.getValue().isNumber()) total += e.getValue().asLong();
            }
            if (total > 0) return total;
        }
        // Form B: metrics.{vertexId}.SourceReceivedCount = 123 or "123"
        long total = 0;
        if (metrics.isObject()) {
            for (var it = metrics.fields(); it.hasNext(); ) {
                Map.Entry<String, JsonNode> e = it.next();
                JsonNode child = e.getValue();
                if (child != null && child.isObject()) {
                    JsonNode v = child.path(key);
                    if (v.isNumber()) total += v.asLong();
                    else if (v.isTextual()) {
                        try { total += Long.parseLong(v.asText().trim()); } catch (NumberFormatException ignored) {}
                    }
                }
            }
        }
        return total;
    }

    private static String pathText(JsonNode root, String key) {
        JsonNode n = root.path(key);
        return n.isMissingNode() || n.isNull() ? null : n.asText(null);
    }

    private static long pathLong(JsonNode root, String key) {
        JsonNode n = root.path(key);
        return n.isNumber() ? n.asLong() : 0L;
    }

    private static String firstNonBlank(String... values) {
        for (String v : values) if (v != null && !v.isBlank()) return v;
        return null;
    }

    private static String stripTrailingSlash(String s) {
        return s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
    }

    private static JobInfo emptyInfo(String jobId) {
        return new JobInfo(jobId, null, null, null, 0, 0, 0, 0, null);
    }
}
