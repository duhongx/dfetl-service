package com.dfygt.dfetl.server.service;

import com.dfygt.dfetl.server.dto.TaskLogResponse;
import com.dfygt.dfetl.server.engine.seatunnel.SeaTunnelProperties;
import com.dfygt.dfetl.server.entity.TaskExecution;
import com.dfygt.dfetl.server.repository.SyncTaskRepository;
import com.dfygt.dfetl.server.repository.TaskExecutionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 日志查询服务：读取 dfetl 本地日志 + SeaTunnel 集群引擎日志，按时间线合并展示完整任务全流程。
 *
 * <p>dfetl 日志：/opt/dfetl-server/logs/dfetl*.log，按 taskId=xxx 标记过滤
 * <p>SeaTunnel 日志：通过 REST API 从集群各节点拉取 seatunnel-engine-server.log，按 [jobId] 前缀过滤
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class LogService {

    private final SyncTaskRepository syncTaskRepository;
    private final TaskExecutionRepository taskExecutionRepository;
    private final SeaTunnelProperties seaTunnelProperties;

    private static final String LOG_DIR = "/opt/dfetl-server/logs";
    private static final String JOB_DIR = "/opt/dfetl-server/jobs";
    private static final int MAX_LINES = 10000;

    private static final Pattern TASK_ID_PATTERN = Pattern.compile("taskId=(\\d+)");
    private static final DateTimeFormatter TIMESTAMP_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");
    // SeaTunnel 日志时间戳格式：yyyy-MM-dd HH:mm:ss,SSS（逗号分隔毫秒）
    private static final DateTimeFormatter ST_TIMESTAMP_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss,SSS");

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    /**
     * 查询任务日志：合并 dfetl 本地日志 + SeaTunnel 引擎日志，按时间线排序。
     */
    public TaskLogResponse getTaskLogs(Long taskId, Long execId) {
        if (!syncTaskRepository.existsById(taskId)) {
            throw new NoSuchElementException("任务不存在: taskId=" + taskId);
        }

        Instant startTime = null;
        Instant endTime = null;
        String engineJobId = null;

        if (execId != null) {
            TaskExecution execution = taskExecutionRepository.findById(execId).orElse(null);
            if (execution != null) {
                startTime = execution.getStartedAt();
                endTime = execution.getFinishedAt();
                engineJobId = execution.getEngineJobId();
            }
        }

        // 1. 获取 dfetl 本地日志（标记 [dfetl]）
        List<String> dfetlLines = filterDfetlLogLines(taskId, startTime, endTime);

        // 2. 获取 SeaTunnel 引擎日志（标记 [seatunnel]）
        List<String> stLines = fetchSeaTunnelLogLines(taskId, execId, engineJobId, startTime, endTime);

        // 3. 合并并按时间排序
        List<String> merged = mergeByTimeline(dfetlLines, stLines);

        // 4. 限制行数
        if (merged.size() > MAX_LINES) {
            merged = new ArrayList<>(merged.subList(0, MAX_LINES));
        }

        String jobConfig = null;
        String jobConfigFile = null;
        if (execId != null) {
            jobConfigFile = "task_" + taskId + "_exec_" + execId + ".json";
            jobConfig = readJobConfig(taskId, execId);
        }

        TaskLogResponse response = new TaskLogResponse();
        response.setLines(merged);
        response.setTotalLines(merged.size());
        response.setJobConfig(jobConfig);
        response.setJobConfigFile(jobConfigFile);
        return response;
    }

    /**
     * 构建下载响应：dfetl 日志 + SeaTunnel 日志 + Job_Config 打包为文本文件。
     */
    public ResponseEntity<byte[]> buildDownloadResponse(Long taskId, Long execId) {
        if (!syncTaskRepository.existsById(taskId)) {
            throw new NoSuchElementException("任务不存在: taskId=" + taskId);
        }

        Instant startTime = null;
        Instant endTime = null;
        String engineJobId = null;

        if (execId != null) {
            TaskExecution execution = taskExecutionRepository.findById(execId).orElse(null);
            if (execution != null) {
                startTime = execution.getStartedAt();
                endTime = execution.getFinishedAt();
                engineJobId = execution.getEngineJobId();
            }
        }

        List<String> dfetlLines = filterDfetlLogLines(taskId, startTime, endTime);
        List<String> stLines = fetchSeaTunnelLogLines(taskId, execId, engineJobId, startTime, endTime);
        List<String> merged = mergeByTimeline(dfetlLines, stLines);

        // 构建下载文件内容
        StringBuilder content = new StringBuilder();
        content.append("========== 任务全流程日志 (taskId=").append(taskId).append(") ==========\n");
        content.append("========== dfetl 调度日志 + SeaTunnel 引擎执行日志（按时间线合并）==========\n\n");

        if (merged.isEmpty()) {
            content.append("该任务暂无日志记录\n");
        } else {
            for (String line : merged) {
                content.append(line).append("\n");
            }
        }

        content.append("\n========== SeaTunnel Job 配置 ==========\n\n");

        String jobConfig = null;
        if (execId != null) {
            jobConfig = readJobConfig(taskId, execId);
        } else {
            jobConfig = readLatestJobConfig(taskId);
        }

        if (jobConfig != null) {
            content.append(jobConfig).append("\n");
        } else {
            content.append("未找到 job 配置文件\n");
        }

        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss"));
        String filename = "task-" + taskId + "-logs-" + timestamp + ".txt";
        byte[] bytes = content.toString().getBytes(StandardCharsets.UTF_8);

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                .contentType(MediaType.TEXT_PLAIN)
                .contentLength(bytes.length)
                .body(bytes);
    }

    // ══════════════════════════════════════════════════════════════════════════
    // dfetl 本地日志
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * 扫描 dfetl 本地日志文件，按 taskId 过滤，返回带 [dfetl] 前缀的日志行。
     */
    List<String> filterDfetlLogLines(Long taskId, Instant startTime, Instant endTime) {
        Path logDir = Paths.get(LOG_DIR);
        if (!Files.isDirectory(logDir) || !Files.isReadable(logDir)) {
            log.warn("dfetl 日志目录不可读: {}", LOG_DIR);
            return List.of();
        }

        String taskIdMarker = "taskId=" + taskId;
        List<String> matchedLines = new ArrayList<>();

        try (DirectoryStream<Path> stream = Files.newDirectoryStream(logDir, "dfetl*.log")) {
            for (Path logFile : stream) {
                if (!Files.isReadable(logFile)) continue;
                try {
                    List<String> fileLines = Files.readAllLines(logFile, StandardCharsets.UTF_8);
                    for (String line : fileLines) {
                        if (!line.contains(taskIdMarker)) continue;
                        if (!matchesExactTaskId(line, taskId)) continue;

                        if (startTime != null || endTime != null) {
                            Instant lineTime = parseDfetlTimestamp(line);
                            if (lineTime == null) continue;
                            if (startTime != null && lineTime.isBefore(startTime)) continue;
                            if (endTime != null && lineTime.isAfter(endTime)) continue;
                        }
                        matchedLines.add("[dfetl] " + line);
                    }
                } catch (IOException e) {
                    log.warn("读取 dfetl 日志文件失败: {}", logFile, e);
                }
            }
        } catch (IOException e) {
            log.warn("无法扫描 dfetl 日志目录: {}", LOG_DIR, e);
        }
        return matchedLines;
    }

    // ══════════════════════════════════════════════════════════════════════════
    // SeaTunnel 集群日志
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * 从 SeaTunnel 集群各节点拉取引擎日志，按 jobId 过滤，返回带 [seatunnel] 前缀的日志行。
     */
    List<String> fetchSeaTunnelLogLines(Long taskId, Long execId, String engineJobId,
                                        Instant startTime, Instant endTime) {
        if (!seaTunnelProperties.enabled()) {
            return List.of();
        }

        // 确定要过滤的 jobId
        String jobId = engineJobId;
        if (jobId == null || jobId.isBlank()) {
            // 如果没有指定 execId，尝试获取最近一次执行的 jobId
            if (execId == null) {
                Optional<TaskExecution> latestExec = taskExecutionRepository.findTopByTaskIdOrderByIdDesc(taskId);
                jobId = latestExec.map(TaskExecution::getEngineJobId).orElse(null);
            }
        }

        if (jobId == null || jobId.isBlank()) {
            log.debug("无法获取 engineJobId，跳过 SeaTunnel 日志拉取: taskId={}", taskId);
            return List.of();
        }

        // 从 /seatunnel/logs 获取所有节点的日志文件 URL
        List<String> logUrls = discoverSeaTunnelLogUrls();
        if (logUrls.isEmpty()) {
            return List.of();
        }

        String jobIdPrefix = "[" + jobId + "]";
        List<String> matchedLines = new ArrayList<>();

        for (String logUrl : logUrls) {
            try {
                List<String> lines = fetchAndFilterSeaTunnelLog(logUrl, jobIdPrefix, startTime, endTime);
                matchedLines.addAll(lines);
            } catch (Exception e) {
                log.warn("拉取 SeaTunnel 日志失败: url={} error={}", logUrl, e.getMessage());
            }
        }

        return matchedLines;
    }

    /**
     * 从 SeaTunnel REST API /logs 端点发现所有节点的 seatunnel-engine-server.log URL。
     */
    private List<String> discoverSeaTunnelLogUrls() {
        String baseUrl = seaTunnelProperties.cluster().restBaseUrl();
        // baseUrl 格式如 http://192.168.1.57:8080/seatunnel
        String logsListUrl = baseUrl + "/logs";

        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(logsListUrl))
                    .GET().timeout(Duration.ofSeconds(5)).build();
            HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() / 100 != 2) {
                log.warn("SeaTunnel logs 列表请求失败: HTTP {}", resp.statusCode());
                return List.of();
            }

            // 解析 HTML 中的 href，提取 seatunnel-engine-server.log 文件 URL
            String body = resp.body();
            List<String> urls = new ArrayList<>();
            Pattern hrefPattern = Pattern.compile("href=\"(http://[^\"]+seatunnel-engine-server\\.log[^\"]*)\"");
            Matcher matcher = hrefPattern.matcher(body);
            while (matcher.find()) {
                String url = matcher.group(1);
                // 只取当天的主日志文件（不含日期后缀的历史文件，除非需要）
                if (!url.contains(".log.")) {
                    urls.add(url);
                }
            }

            // 如果没有找到不带日期后缀的，也包含带日期后缀的
            if (urls.isEmpty()) {
                matcher.reset();
                while (matcher.find()) {
                    urls.add(matcher.group(1));
                }
            }

            return urls;
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            log.warn("发现 SeaTunnel 日志 URL 失败: {}", e.getMessage());
            return List.of();
        }
    }

    /**
     * 从指定 URL 拉取 SeaTunnel 日志文件，按 jobId 前缀过滤，返回带 [seatunnel] 标记的行。
     * 使用流式读取避免大文件 OOM。
     */
    private List<String> fetchAndFilterSeaTunnelLog(String logUrl, String jobIdPrefix,
                                                    Instant startTime, Instant endTime) throws IOException, InterruptedException {
        HttpRequest req = HttpRequest.newBuilder(URI.create(logUrl))
                .GET().timeout(Duration.ofSeconds(30)).build();
        HttpResponse<java.io.InputStream> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofInputStream());

        if (resp.statusCode() / 100 != 2) {
            log.debug("SeaTunnel 日志文件请求失败: url={} HTTP {}", logUrl, resp.statusCode());
            return List.of();
        }

        List<String> matched = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(resp.body(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (!line.startsWith(jobIdPrefix)) continue;

                // 时间范围过滤
                if (startTime != null || endTime != null) {
                    Instant lineTime = parseSeaTunnelTimestamp(line);
                    if (lineTime == null) continue;
                    if (startTime != null && lineTime.isBefore(startTime)) continue;
                    if (endTime != null && lineTime.isAfter(endTime)) continue;
                }

                // 去掉 [jobId] 前缀，加上 [seatunnel] 标记
                String content = line.substring(jobIdPrefix.length()).trim();
                matched.add("[seatunnel] " + content);

                // 防止单个文件拉取过多
                if (matched.size() >= MAX_LINES) break;
            }
        }
        return matched;
    }

    // ══════════════════════════════════════════════════════════════════════════
    // 时间线合并
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * 将 dfetl 日志和 SeaTunnel 日志按时间戳合并排序。
     */
    private List<String> mergeByTimeline(List<String> dfetlLines, List<String> stLines) {
        List<String> all = new ArrayList<>(dfetlLines.size() + stLines.size());
        all.addAll(dfetlLines);
        all.addAll(stLines);

        all.sort(Comparator.comparing(line -> {
            Instant ts = extractTimestamp(line);
            return ts != null ? ts : Instant.MIN;
        }));

        return all;
    }

    /**
     * 从带 [dfetl] 或 [seatunnel] 前缀的行中提取时间戳。
     */
    private Instant extractTimestamp(String line) {
        if (line.startsWith("[dfetl] ")) {
            return parseDfetlTimestamp(line.substring(8));
        } else if (line.startsWith("[seatunnel] ")) {
            return parseSeaTunnelTimestamp2(line.substring(12));
        }
        return null;
    }

    // ══════════════════════════════════════════════════════════════════════════
    // 时间戳解析
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * 解析 dfetl 日志行首 yyyy-MM-dd HH:mm:ss.SSS 时间戳。
     */
    Instant parseDfetlTimestamp(String logLine) {
        if (logLine == null || logLine.length() < 23) return null;
        String ts = logLine.substring(0, 23);
        try {
            LocalDateTime ldt = LocalDateTime.parse(ts, TIMESTAMP_FORMATTER);
            return ldt.atZone(ZoneId.systemDefault()).toInstant();
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    /**
     * 解析 SeaTunnel 原始日志行时间戳。
     * 格式：[jobId] yyyy-MM-dd HH:mm:ss,SSS LEVEL ...
     * jobIdPrefix 已被去掉后的格式：yyyy-MM-dd HH:mm:ss,SSS LEVEL ...
     */
    private Instant parseSeaTunnelTimestamp(String rawLine) {
        // rawLine 格式: [jobId] yyyy-MM-dd HH:mm:ss,SSS ...
        // 找到 ] 后的时间戳
        int bracketEnd = rawLine.indexOf(']');
        if (bracketEnd < 0) return null;
        String afterBracket = rawLine.substring(bracketEnd + 1).trim();
        return parseSeaTunnelTimestamp2(afterBracket);
    }

    /**
     * 解析去掉前缀后的 SeaTunnel 时间戳：yyyy-MM-dd HH:mm:ss,SSS
     */
    private Instant parseSeaTunnelTimestamp2(String line) {
        if (line == null || line.length() < 23) return null;
        String ts = line.substring(0, 23);
        try {
            LocalDateTime ldt = LocalDateTime.parse(ts, ST_TIMESTAMP_FORMATTER);
            return ldt.atZone(ZoneId.systemDefault()).toInstant();
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // 辅助方法
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * 精确匹配 taskId，避免 taskId=5 匹配到 taskId=50。
     */
    private boolean matchesExactTaskId(String line, Long taskId) {
        Matcher matcher = TASK_ID_PATTERN.matcher(line);
        while (matcher.find()) {
            if (matcher.group(1).equals(String.valueOf(taskId))) {
                return true;
            }
        }
        return false;
    }

    /**
     * 读取指定 taskId 和 execId 的 Job_Config 文件内容。
     */
    String readJobConfig(Long taskId, Long execId) {
        Path configPath = Paths.get(JOB_DIR, "task_" + taskId + "_exec_" + execId + ".json");
        if (!Files.exists(configPath) || !Files.isReadable(configPath)) return null;
        try {
            return Files.readString(configPath, StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.warn("读取 Job_Config 文件失败: {}", configPath, e);
            return null;
        }
    }

    /**
     * 读取最近一次执行的 Job_Config。
     */
    private String readLatestJobConfig(Long taskId) {
        return taskExecutionRepository.findTopByTaskIdOrderByIdDesc(taskId)
                .map(exec -> readJobConfig(taskId, exec.getId()))
                .orElse(null);
    }

    // 保留旧方法签名兼容（内部使用）
    Instant parseTimestamp(String logLine) {
        return parseDfetlTimestamp(logLine);
    }
}
