package com.dfygt.dfetl.server.service.publish;

import com.dfygt.dfetl.server.config.MessagePublishProperties;
import com.dfygt.dfetl.server.entity.Institution;
import com.dfygt.dfetl.server.entity.MessagePublishConfig;
import com.dfygt.dfetl.server.entity.MessagePublishLog;
import com.dfygt.dfetl.server.entity.SourceDataSource;
import com.dfygt.dfetl.server.entity.SyncTask;
import com.dfygt.dfetl.server.repository.InstitutionRepository;
import com.dfygt.dfetl.server.repository.MessagePublishConfigRepository;
import com.dfygt.dfetl.server.repository.MessagePublishLogRepository;
import com.dfygt.dfetl.server.repository.SourceDataSourceRepository;
import com.dfygt.dfetl.server.repository.SyncTaskRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 消息发布触发器 — 同步成功后的入口，根据 dataScope 选择增量/全量策略。
 * <p>
 * 核心原则：
 * <ul>
 *   <li>异步隔离：在独立线程池执行，不阻塞同步主流程</li>
 *   <li>失败不回滚：发布失败不影响同步结果和水位线推进</li>
 *   <li>流式 + 批量：变更行流式读取 + Redis Pipeline 批量发送，避免 OOM 和性能爆炸</li>
 *   <li>方法内部捕获所有异常，绝不向外抛出</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class MessagePublishTrigger {

    /** 历史任务未保存 pageSize 时的安全默认批次。 */
    private static final int DEFAULT_PUBLISH_BATCH_SIZE = 200;

    /** 字段映射缓存：taskId → mapping，5 分钟 TTL */
    private final Map<Long, CachedMapping> fieldMappingCache = new ConcurrentHashMap<>();
    private static final long MAPPING_CACHE_TTL_MS = 5 * 60 * 1000L; // 5 minutes

    private record CachedMapping(Map<String, String> mapping, long cachedAt) {
        boolean isExpired() {
            return System.currentTimeMillis() - cachedAt > MAPPING_CACHE_TTL_MS;
        }
    }

    private record MedicalPublishPlan(
            boolean contractDriven,
            Set<String> payloadColumns,
            String messageKeyTemplate
    ) {
        static MedicalPublishPlan empty() {
            return new MedicalPublishPlan(false, Set.of(), null);
        }
    }

    private final MessagePublishConfigRepository configRepository;
    private final ChangeDataReader changeDataReader;
    private final MessageBuilder messageBuilder;
    private final MessagePublisher messagePublisher;
    private final MessagePublishLogRepository logRepository;
    private final SyncTaskRepository syncTaskRepository;
    private final ObjectMapper objectMapper;
    private final MessagePublishProperties publishProperties;
    private final SourceDataSourceRepository sourceDataSourceRepository;
    private final InstitutionRepository institutionRepository;
    private final MessagePublishRunService publishRunService;
    private final MessageBatchVisibilityService batchVisibilityService;
    private final ThreadLocal<Long> activePublishLogId = new ThreadLocal<>();

    /**
     * 在提交异步任务前同步建立 PENDING 证据；无启用配置时返回 null。
     */
    public Long preparePublishRun(Long taskId, Long executionId, String dataScope,
                                  Instant windowStart, Instant windowEnd) {
        return publishRunService.prepareOriginal(
                taskId, executionId, dataScope, windowStart, windowEnd);
    }

    /**
     * 同步成功后异步触发消息发布。
     */
    @Async("messagePublishExecutor")
    public void onSyncSuccess(Long taskId, Long batchId, String dataScope,
                              Instant windowStart, Instant windowEnd) {
        MessagePublishConfig config = null;
        Long publishLogId = null;
        try {
            Optional<MessagePublishConfig> configOpt = configRepository.findByTaskId(taskId);
            if (configOpt.isEmpty() || !configOpt.get().isEnabled()) {
                log.debug("MessagePublish: task {} has no config or disabled, skip", taskId);
                return;
            }
            config = configOpt.get();
            publishLogId = publishRunService.prepareOriginal(
                    taskId, batchId, dataScope, windowStart, windowEnd);
            activePublishLogId.set(publishLogId);
            publishRunService.markRunning(publishLogId);

            if ("INCREMENTAL".equals(dataScope)) {
                publishIncrementalByBatch(config, batchId, batchId, windowStart, windowEnd);
            } else {
                publishFull(config, batchId);
            }
        } catch (Exception e) {
            log.error("MessagePublish: unexpected error for task={}, batch={}", taskId, batchId, e);
            saveLog(taskId, batchId,
                    config != null ? config.getChannel() : "unknown",
                    config != null ? config.getTopic() : "unknown",
                    0, "FAILED", truncateError(e.getMessage()), dataScope, windowStart, windowEnd, null);
        } finally {
            activePublishLogId.remove();
        }
    }

    /**
     * 重发指定 execution 的原始目标行范围；publishLogId 区分本次尝试。
     */
    @Async("messagePublishExecutor")
    public void retryExecution(Long publishLogId, Long executionId) {
        MessagePublishConfig config = null;
        MessagePublishLog retry = null;
        try {
            retry = logRepository.findById(publishLogId)
                    .orElseThrow(() -> new NoSuchElementException(
                            "MessagePublishLog not found: " + publishLogId));
            activePublishLogId.set(publishLogId);
            Long retryTaskId = retry.getTaskId();
            config = configRepository.findByTaskId(retryTaskId)
                    .filter(MessagePublishConfig::isEnabled)
                    .orElseThrow(() -> new IllegalStateException(
                            "MessagePublishConfig missing or disabled for taskId="
                                    + retryTaskId));
            publishRunService.markRunning(publishLogId);
            if ("INCREMENTAL".equalsIgnoreCase(retry.getDataScope())) {
                publishIncrementalByBatch(
                        config, executionId, retry.getBatchId(),
                        retry.getWindowStart(), retry.getWindowEnd());
            } else {
                publishFull(config, retry.getBatchId());
            }
        } catch (Exception e) {
            saveLog(retry == null ? null : retry.getTaskId(),
                    retry == null ? -Math.abs(executionId) : retry.getBatchId(),
                    config == null ? "unknown" : config.getChannel(),
                    config == null ? "unknown" : config.getTopic(),
                    0, "FAILED", truncateError(e.getMessage()),
                    retry == null ? null : retry.getDataScope(),
                    retry == null ? null : retry.getWindowStart(),
                    retry == null ? null : retry.getWindowEnd(), null);
        } finally {
            activePublishLogId.remove();
        }
    }

    /**
     * 增量发布：流式查询 + 批量发送。
     */
    void publishIncremental(MessagePublishConfig config, Long batchId,
                            Instant windowStart, Instant windowEnd) {
        Long taskId = config.getTaskId();
        String targetTable = resolveTargetTable(taskId);
        Long targetDsId = resolveTargetDsId(taskId);

        if (targetTable == null || targetDsId == null) {
            log.warn("MessagePublish: cannot resolve target table/ds for task={}", taskId);
            saveLog(taskId, batchId, config.getChannel(), config.getTopic(),
                    0, "FAILED", "Cannot resolve target table or datasource",
                    "INCREMENTAL", windowStart, windowEnd, null);
            return;
        }

        // 等待 Doris 数据可见（Stream Load 写入后 tablet publish 有延迟）
        try {
            Thread.sleep(publishProperties.getDorisVisibilityDelayMs());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            saveLog(taskId, batchId, config.getChannel(), config.getTopic(),
                    0, "FAILED", "message publish interrupted",
                    "INCREMENTAL", windowStart, windowEnd, null);
            return;
        }

        if (windowStart == null || windowEnd == null) {
            log.warn("MessagePublish: incremental requires windowStart/windowEnd, task={}", taskId);
            saveLog(taskId, batchId, config.getChannel(), config.getTopic(),
                    0, "FAILED", "windowStart or windowEnd is null", "INCREMENTAL", windowStart, windowEnd, null);
            return;
        }

        Map<String, String> fieldMapping = getFieldMapping(config, taskId);
        String yiLiaoJgDm = resolveYiLiaoJgDm(taskId);
        MedicalPublishPlan medicalPlan = medicalPublishPlan(config, taskId, fieldMapping);

        AtomicInteger published = new AtomicInteger(0);
        AtomicInteger failed = new AtomicInteger(0);
        int publishBatchSize = resolvePublishBatchSize(config);
        List<String> buffer = new ArrayList<>(publishBatchSize);
        List<String> sampleCollector = new ArrayList<>(5);

        try {
            // spec message-publish-tenant-scope · Bug 1：传 taskId 收口任务范围
            changeDataReader.streamByWindow(targetDsId, targetTable, taskId, windowStart, windowEnd, row -> {
                String json = serializeOrNull(row, fieldMapping, config, "UPSERT", yiLiaoJgDm, medicalPlan, failed);
                if (json != null) {
                    if (sampleCollector.size() < 5) {
                        sampleCollector.add(json);
                    }
                    buffer.add(json);
                    if (buffer.size() >= publishBatchSize) {
                        int flushed = buffer.size();
                        flushBuffer(buffer, config, batchId, published, failed);
                        applyRateLimit(config.getRateLimit(), flushed);
                    }
                }
            });
            // flush 残余
            if (!buffer.isEmpty()) {
                int flushed = buffer.size();
                flushBuffer(buffer, config, batchId, published, failed);
                applyRateLimit(config.getRateLimit(), flushed);
            }
        } catch (Exception e) {
            log.error("MessagePublish: stream incremental aborted task={}, batch={}, published so far={}",
                    taskId, batchId, published.get(), e);
            saveLog(taskId, batchId, config.getChannel(), config.getTopic(),
                    published.get(),
                    published.get() > 0 ? "PARTIAL" : "FAILED",
                    truncateError(e.getMessage()), "INCREMENTAL", windowStart, windowEnd,
                    serializeSamples(sampleCollector));
            return;
        }

        String status = failed.get() == 0
                ? (published.get() == 0 ? "SUCCESS" : "SUCCESS")
                : (published.get() == 0 ? "FAILED" : "PARTIAL");
        String errorMsg = failed.get() > 0 ? "Failed rows: " + failed.get() : null;
        saveLog(taskId, batchId, config.getChannel(), config.getTopic(),
                published.get(), status, errorMsg, "INCREMENTAL", windowStart, windowEnd,
                serializeSamples(sampleCollector));

        log.info("MessagePublish: incremental done task={}, batch={}, published={}, failed={}",
                taskId, batchId, published.get(), failed.get());
    }

    /**
     * 自动增量发布：按本次 executionId / _etl_batch_id 读取实际写入行。
     *
     * <p>{@code readBatchId} 是目标表 `_etl_batch_id`；{@code logBatchId} 是本次发布日志的 batchId。
     * 重发时两者可能不同：读取原始同步批次，记录新的负数重发批次。
     */
    void publishIncrementalByBatch(MessagePublishConfig config, Long readBatchId, Long logBatchId,
                                   Instant windowStart, Instant windowEnd) {
        Long taskId = config.getTaskId();
        String targetTable = resolveTargetTable(taskId);
        Long targetDsId = resolveTargetDsId(taskId);

        if (targetTable == null || targetDsId == null) {
            log.warn("MessagePublish: cannot resolve target table/ds for task={}", taskId);
            saveLog(taskId, logBatchId, config.getChannel(), config.getTopic(),
                    0, "FAILED", "Cannot resolve target table or datasource",
                    "INCREMENTAL", windowStart, windowEnd, null);
            return;
        }
        if (readBatchId == null) {
            saveLog(taskId, logBatchId, config.getChannel(), config.getTopic(),
                    0, "FAILED", "batchId is null", "INCREMENTAL", windowStart, windowEnd, null);
            return;
        }

        try {
            batchVisibilityService.awaitVisibleRows(
                    targetDsId, targetTable, taskId, readBatchId);
        } catch (MessageBatchNotVisibleException e) {
            saveLog(taskId, logBatchId, config.getChannel(), config.getTopic(),
                    0, "WAIT_RETRY", truncateError(e.getMessage()),
                    "INCREMENTAL", windowStart, windowEnd, null);
            return;
        }

        Map<String, String> fieldMapping = getFieldMapping(config, taskId);
        String yiLiaoJgDm = resolveYiLiaoJgDm(taskId);
        MedicalPublishPlan medicalPlan = medicalPublishPlan(config, taskId, fieldMapping);
        AtomicInteger published = new AtomicInteger(0);
        AtomicInteger failed = new AtomicInteger(0);
        int publishBatchSize = resolvePublishBatchSize(config);
        List<String> buffer = new ArrayList<>(publishBatchSize);
        List<String> sampleCollector = new ArrayList<>(5);

        try {
            changeDataReader.streamByBatch(targetDsId, targetTable, taskId, readBatchId, row -> {
                String json = serializeOrNull(row, fieldMapping, config, "UPSERT", yiLiaoJgDm, medicalPlan, failed);
                if (json != null) {
                    if (sampleCollector.size() < 5) {
                        sampleCollector.add(json);
                    }
                    buffer.add(json);
                    if (buffer.size() >= publishBatchSize) {
                        int flushed = buffer.size();
                        flushBuffer(buffer, config, logBatchId, published, failed);
                        applyRateLimit(config.getRateLimit(), flushed);
                    }
                }
            });
            if (!buffer.isEmpty()) {
                int flushed = buffer.size();
                flushBuffer(buffer, config, logBatchId, published, failed);
                applyRateLimit(config.getRateLimit(), flushed);
            }
        } catch (Exception e) {
            log.error("MessagePublish: batch incremental aborted task={}, readBatch={}, logBatch={}, published so far={}",
                    taskId, readBatchId, logBatchId, published.get(), e);
            saveLog(taskId, logBatchId, config.getChannel(), config.getTopic(),
                    published.get(),
                    published.get() > 0 ? "PARTIAL" : "FAILED",
                    truncateError(e.getMessage()), "INCREMENTAL", windowStart, windowEnd,
                    serializeSamples(sampleCollector));
            return;
        }

        String status = failed.get() == 0
                ? "SUCCESS"
                : (published.get() == 0 ? "FAILED" : "PARTIAL");
        String errorMsg = failed.get() > 0 ? "Failed rows: " + failed.get() : null;
        saveLog(taskId, logBatchId, config.getChannel(), config.getTopic(),
                published.get(), status, errorMsg, "INCREMENTAL", windowStart, windowEnd,
                serializeSamples(sampleCollector));

        log.info("MessagePublish: batch incremental done task={}, readBatch={}, logBatch={}, published={}, failed={}",
                taskId, readBatchId, logBatchId, published.get(), failed.get());
    }

    /** 全量发布：按 fullSyncMode 执行跳过、完成通知或全表流式发布。 */
    void publishFull(MessagePublishConfig config, Long batchId) {
        Long taskId = config.getTaskId();
        FullSyncMode mode = FullSyncMode.parse(config.getFullSyncMode());
        if (mode == FullSyncMode.SKIP) {
            saveLog(taskId, batchId, config.getChannel(), config.getTopic(),
                    0, "SKIPPED", null, "FULL", null, null, null);
            log.info("MessagePublish: full skipped task={}, batch={}", taskId, batchId);
            return;
        }
        if (mode == FullSyncMode.NOTIFY_ONLY) {
            publishFullCompletionSignal(config, batchId);
            return;
        }

        String targetTable = resolveTargetTable(taskId);
        Long targetDsId = resolveTargetDsId(taskId);

        if (targetTable == null || targetDsId == null) {
            saveLog(taskId, batchId, config.getChannel(), config.getTopic(),
                    0, "FAILED", "Cannot resolve target table or datasource", "FULL", null, null, null);
            return;
        }

        // 等待 Doris 数据可见（Stream Load 写入后 tablet publish 有延迟）
        try {
            Thread.sleep(publishProperties.getDorisVisibilityDelayMs());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            saveLog(taskId, batchId, config.getChannel(), config.getTopic(),
                    0, "FAILED", "message publish interrupted",
                    "FULL", null, null, null);
            return;
        }

        Integer rateLimit = config.getRateLimit();
        Map<String, String> fieldMapping = getFieldMapping(config, taskId);
        String yiLiaoJgDm = resolveYiLiaoJgDm(taskId);
        MedicalPublishPlan medicalPlan = medicalPublishPlan(config, taskId, fieldMapping);

        AtomicInteger published = new AtomicInteger(0);
        AtomicInteger failed = new AtomicInteger(0);
        int publishBatchSize = resolvePublishBatchSize(config);
        List<String> buffer = new ArrayList<>(publishBatchSize);
        List<String> sampleCollector = new ArrayList<>(5);

        try {
            // 流式读取 + 批量发送（全表流式，不依赖主键唯一性）
            // spec message-publish-tenant-scope · Bug 2：传 taskId 收口任务范围
            long total = changeDataReader.streamFull(targetDsId, targetTable, taskId, row -> {
                String json = serializeOrNull(row, fieldMapping, config, "UPSERT", yiLiaoJgDm, medicalPlan, failed);
                if (json != null) {
                    if (sampleCollector.size() < 5) {
                        sampleCollector.add(json);
                    }
                    buffer.add(json);
                    if (buffer.size() >= publishBatchSize) {
                        int flushed = buffer.size();
                        flushBuffer(buffer, config, batchId, published, failed);
                        applyRateLimit(rateLimit, flushed);
                    }
                }
            });
            log.info("MessagePublish: streamed {} rows from {}", total, targetTable);

            if (!buffer.isEmpty()) {
                int flushed = buffer.size();
                flushBuffer(buffer, config, batchId, published, failed);
                applyRateLimit(rateLimit, flushed);
            }

            String status = failed.get() == 0 ? "SUCCESS" : (published.get() == 0 ? "FAILED" : "PARTIAL");
            String errorMsg = failed.get() > 0 ? "Failed rows: " + failed.get() : null;
            saveLog(taskId, batchId, config.getChannel(), config.getTopic(),
                    published.get(), status, errorMsg, "FULL", null, null,
                    serializeSamples(sampleCollector));
            log.info("MessagePublish: full done task={}, published={}, failed={}",
                    taskId, published.get(), failed.get());
        } catch (Exception e) {
            log.error("MessagePublish: full aborted task={}, published so far={}",
                    taskId, published.get(), e);
            String status = published.get() > 0 ? "PARTIAL" : "FAILED";
            saveLog(taskId, batchId, config.getChannel(), config.getTopic(),
                    published.get(), status, truncateError(e.getMessage()), "FULL", null, null,
                    serializeSamples(sampleCollector));
        }
    }

    private void publishFullCompletionSignal(MessagePublishConfig config, Long batchId) {
        Long taskId = config.getTaskId();
        try {
            String yiLiaoJgDm = resolveYiLiaoJgDm(taskId);
            EtlMessage signal = messageBuilder.buildSignal(
                    config, "FULL_SYNC_COMPLETE", yiLiaoJgDm);
            String json = objectMapper.writeValueAsString(signal);
            PublishBatchResult result = messagePublisher.publishBatch(
                    List.of(json), config.getTopic(),
                    new MessagePublishContext(
                            taskId, batchId, config.getTopic(), activePublishLogId.get()));
            if (result.failedCount() > 0) {
                String error = result.outcomes().stream()
                        .filter(outcome -> outcome.status() != PublishOutcome.Status.SENT)
                        .map(PublishOutcome::error)
                        .filter(Objects::nonNull)
                        .findFirst()
                        .orElse("completion signal was not confirmed by broker");
                throw new IllegalStateException(error);
            }
            saveLog(taskId, batchId, config.getChannel(), config.getTopic(),
                    1, "SUCCESS", null, "FULL", null, null,
                    serializeSamples(List.of(json)));
            log.info("MessagePublish: full completion signal published task={}, batch={}",
                    taskId, batchId);
        } catch (Exception e) {
            log.error("MessagePublish: full completion signal failed task={}, batch={}",
                    taskId, batchId, e);
            saveLog(taskId, batchId, config.getChannel(), config.getTopic(),
                    0, "FAILED", truncateError(e.getMessage()), "FULL", null, null, null);
        }
    }

    /**
     * 按 logId 重发某次发布（基于 log 反查 taskId/batchId/window），使用当前发布配置。
     */
    public void republishByLogId(Long logId) {
        Optional<MessagePublishLog> logOpt = logRepository.findById(logId);
        if (logOpt.isEmpty()) {
            log.warn("MessagePublish: republishByLogId failed, log not found id={}", logId);
            throw new java.util.NoSuchElementException("MessagePublishLog not found: id=" + logId);
        }
        MessagePublishLog originalLog = logOpt.get();
        Optional<MessagePublishConfig> configOpt = configRepository.findByTaskId(originalLog.getTaskId());
        if (configOpt.isEmpty()) {
            log.warn("MessagePublish: republishByLogId failed, no config task={}", originalLog.getTaskId());
            throw new IllegalStateException(
                    "MessagePublishConfig not found for taskId=" + originalLog.getTaskId()
                            + "（log=" + logId + " 的任务已无发布配置，无法重发）");
        }
        MessagePublishConfig currentConfig = configOpt.get();

        // 重发使用新的 batchId（基于负数原 batchId）以便区分重发记录
        Long newBatchId = originalLog.getBatchId() != null
                ? -Math.abs(originalLog.getBatchId())
                : -System.currentTimeMillis();

        if ("INCREMENTAL".equals(originalLog.getDataScope())) {
            if (originalLog.getBatchId() != null && originalLog.getBatchId() > 0) {
                publishIncrementalByBatch(
                        currentConfig, originalLog.getBatchId(), newBatchId,
                        originalLog.getWindowStart(), originalLog.getWindowEnd());
            } else {
                if (originalLog.getWindowStart() == null || originalLog.getWindowEnd() == null) {
                    log.warn("MessagePublish: republishByLogId failed, original log has no window or positive batchId logId={}", logId);
                    throw new IllegalStateException(
                            "原始日志 logId=" + logId + " 缺少 windowStart/windowEnd，无法重发增量数据");
                }
                publishIncremental(currentConfig, newBatchId, originalLog.getWindowStart(), originalLog.getWindowEnd());
            }
        } else {
            publishFull(currentConfig, newBatchId);
        }
    }

    /** 按时间窗口重发（任意 taskId） */
    public void republishByTimeWindow(Long taskId, Instant start, Instant end) {
        Optional<MessagePublishConfig> configOpt = configRepository.findByTaskId(taskId);
        if (configOpt.isEmpty()) {
            log.warn("MessagePublish: republishByTimeWindow failed, no config task={}", taskId);
            throw new IllegalStateException(
                    "MessagePublishConfig not found for taskId=" + taskId + "，无法重发");
        }
        Long batchId = -System.currentTimeMillis();
        publishIncremental(configOpt.get(), batchId, start, end);
    }

    // ── 内部辅助 ────────────────────────────────────────────────────────

    /** 序列化单行；失败时计数 +1 并返回 null */
    private String serializeOrNull(Map<String, Object> row, Map<String, String> mapping,
                                   MessagePublishConfig config, String op, AtomicInteger failed) {
        return serializeOrNull(row, mapping, config, op, resolveYiLiaoJgDm(config.getTaskId()), failed);
    }

    /** 序列化单行；失败时计数 +1 并返回 null */
    private String serializeOrNull(Map<String, Object> row, Map<String, String> mapping,
                                   MessagePublishConfig config, String op, String yiLiaoJgDm,
                                   AtomicInteger failed) {
        return serializeOrNull(row, mapping, config, op, yiLiaoJgDm, MedicalPublishPlan.empty(), failed);
    }

    /** 序列化单行；失败时计数 +1 并返回 null */
    private String serializeOrNull(Map<String, Object> row, Map<String, String> mapping,
                                   MessagePublishConfig config, String op, String yiLiaoJgDm,
                                   MedicalPublishPlan medicalPlan, AtomicInteger failed) {
        try {
            Map<String, Object> effectiveRow = medicalPlan.contractDriven()
                    ? filterMedicalPayloadRow(row, medicalPlan.payloadColumns())
                    : row;
            EtlMessage msg = medicalPlan.contractDriven()
                    ? messageBuilder.build(effectiveRow, mapping, config, op, yiLiaoJgDm, medicalPlan.messageKeyTemplate())
                    : messageBuilder.build(effectiveRow, mapping, config, op, yiLiaoJgDm);
            return objectMapper.writeValueAsString(msg);
        } catch (Exception e) {
            failed.incrementAndGet();
            log.warn("MessagePublish: row serialize failed: {}", e.getMessage());
            return null;
        }
    }

    private Map<String, Object> filterMedicalPayloadRow(Map<String, Object> row, Set<String> payloadColumns) {
        if (row == null || row.isEmpty()) {
            return row;
        }
        if (payloadColumns == null || payloadColumns.isEmpty()) {
            return Collections.emptyMap();
        }
        Map<String, Object> filtered = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : row.entrySet()) {
            String key = entry.getKey();
            if (key != null && payloadColumns.contains(key.toLowerCase(Locale.ROOT))) {
                filtered.put(key, entry.getValue());
            }
        }
        return filtered;
    }

    /** 把 buffer 中的消息批量 pipeline 发送，并清空 buffer */
    private void flushBuffer(List<String> buffer, MessagePublishConfig config, Long batchId,
                             AtomicInteger published, AtomicInteger failed) {
        if (buffer.isEmpty()) return;
        int size = buffer.size();
        try {
            PublishBatchResult result = java.util.Objects.requireNonNull(messagePublisher.publishBatch(
                    buffer,
                    config.getTopic(),
                    new MessagePublishContext(config.getTaskId(), batchId, config.getTopic(),
                            activePublishLogId.get())),
                    "MessagePublisher returned null PublishBatchResult");
            if (result.outcomes().size() != size) {
                throw new IllegalStateException("消息发布终态数量不一致: requested=" + size
                        + ", terminal=" + result.outcomes().size());
            }
            published.addAndGet(result.sentCount());
            failed.addAndGet(result.failedCount());
        } catch (Exception e) {
            failed.addAndGet(size);
            log.warn("MessagePublish: flush batch failed size={}, error={}", size, e.getMessage());
        }
        buffer.clear();
    }

    /** 限速：基于批次的 sleep（避免每条 sleep 阻塞过多） */
    private void applyRateLimit(Integer rateLimit, int batchSize) {
        if (rateLimit == null || rateLimit <= 0) return;
        long sleepMs = (long) batchSize * 1000L / rateLimit;
        if (sleepMs > 0) {
            try {
                Thread.sleep(Math.min(sleepMs, 5000)); // 单次最多 5 秒
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private int resolvePublishBatchSize(MessagePublishConfig config) {
        if (config == null || config.getPageSize() == null || config.getPageSize() <= 0) {
            return DEFAULT_PUBLISH_BATCH_SIZE;
        }
        return config.getPageSize();
    }

    /** 字段映射加载（带 5 分钟 TTL 缓存，避免每次 publish 都查库） */
    private Map<String, String> getFieldMapping(MessagePublishConfig config, Long taskId) {
        // Check cache first
        CachedMapping cached = fieldMappingCache.get(taskId);
        if (cached != null && !cached.isExpired()) {
            return cached.mapping();
        }

        // Load from registry or config
        Map<String, String> mapping = loadFieldMappingFromSource(config, taskId);

        // Cache the result
        fieldMappingCache.put(taskId, new CachedMapping(mapping, System.currentTimeMillis()));
        return mapping;
    }

    /** 实际加载字段映射逻辑：只读取任务创建时固化的快照。 */
    private Map<String, String> loadFieldMappingFromSource(MessagePublishConfig config, Long taskId) {
        if (config.getFieldMappingJson() != null && !config.getFieldMappingJson().isBlank()) {
            try {
                com.fasterxml.jackson.databind.JsonNode root = objectMapper.readTree(config.getFieldMappingJson());
                Map<String, String> mapping = new LinkedHashMap<>();
                if (root.isObject()) {
                    root.fields().forEachRemaining(entry -> mapping.put(entry.getKey(), entry.getValue().asText()));
                } else if (root.isArray()) {
                    for (com.fasterxml.jackson.databind.JsonNode item : root) {
                        String source = jsonText(item, "sourceField");
                        String target = jsonText(item, "targetField");
                        String standard = jsonText(item, "standardField");
                        String mapped = standard == null ? target : standard;
                        if (mapped == null) continue;
                        if (source != null) mapping.put(source, mapped);
                        if (target != null) mapping.put(target, mapped);
                    }
                }
                return mapping;
            } catch (Exception e) {
                log.warn("MessagePublish: parse fieldMappingJson failed task={}", taskId, e);
            }
        }
        return Collections.emptyMap();
    }

    private MedicalPublishPlan medicalPublishPlan(MessagePublishConfig config, Long taskId,
                                                   Map<String, String> fieldMapping) {
        try {
            Optional<SyncTask> taskOpt = syncTaskRepository.findById(taskId);
            if (taskOpt.isEmpty() || !isContractDriven(taskOpt.get())) {
                return MedicalPublishPlan.empty();
            }
            Set<String> payloadColumns = fieldMapping == null ? Set.of() : fieldMapping.keySet().stream()
                    .filter(Objects::nonNull)
                    .map(value -> value.toLowerCase(Locale.ROOT))
                    .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
            return new MedicalPublishPlan(true, payloadColumns, config.getMessageKeyTemplate());
        } catch (Exception e) {
            log.warn("MessagePublish: snapshot publish plan failed task={}: {}", taskId, e.getMessage());
            return MedicalPublishPlan.empty();
        }
    }

    private static String jsonText(com.fasterxml.jackson.databind.JsonNode node, String field) {
        if (node == null || node.get(field) == null || node.get(field).isNull()) return null;
        String value = node.get(field).asText();
        return value == null || value.isBlank() ? null : value.trim();
    }

    private boolean isContractDriven(SyncTask task) {
        String mode = parseDataCharacteristics(task).get("medicalMappingMode");
        return "CONTRACT_DRIVEN".equalsIgnoreCase(mode);
    }

    private Map<String, String> parseDataCharacteristics(SyncTask task) {
        if (task == null || task.getDataCharacteristics() == null || task.getDataCharacteristics().isBlank()) {
            return Collections.emptyMap();
        }
        try {
            return objectMapper.readValue(task.getDataCharacteristics(), new TypeReference<>() {});
        } catch (Exception e) {
            log.debug("MessagePublish: parse dataCharacteristics failed task={}: {}",
                    task.getId(), e.getMessage());
            return Collections.emptyMap();
        }
    }

    String resolveYiLiaoJgDm(Long taskId) {
        if (taskId == null) {
            return "";
        }
        Optional<SyncTask> taskOpt = syncTaskRepository.findById(taskId);
        if (taskOpt.isEmpty()) {
            return "";
        }
        SyncTask task = taskOpt.get();
        String taskInstitutionCode = findInstitutionCode(task.getInstitutionId());
        if (!taskInstitutionCode.isBlank()) {
            return taskInstitutionCode;
        }
        if (task.getSourceDataSourceId() == null) {
            return "";
        }
        return sourceDataSourceRepository.findById(task.getSourceDataSourceId())
                .map(SourceDataSource::getInstitutionId)
                .map(this::findInstitutionCode)
                .filter(s -> !s.isBlank())
                .orElse("");
    }

    private String findInstitutionCode(Long institutionId) {
        if (institutionId == null) {
            return "";
        }
        return institutionRepository.findById(institutionId)
                .map(Institution::getCode)
                .filter(s -> s != null && !s.isBlank())
                .orElse("");
    }

    private String resolveTargetTable(Long taskId) {
        Optional<SyncTask> taskOpt = syncTaskRepository.findById(taskId);
        if (taskOpt.isEmpty()) return null;
        SyncTask task = taskOpt.get();
        if (task.getTargetTableMap() != null && !task.getTargetTableMap().isBlank()) {
            try {
                Map<String, String> tableMap = objectMapper.readValue(task.getTargetTableMap(), new TypeReference<>() {});
                if (!tableMap.isEmpty()) return tableMap.values().iterator().next();
            } catch (Exception e) {
                log.warn("parse targetTableMap failed task={}", taskId, e);
            }
        }
        if (task.getViewNames() != null && !task.getViewNames().isEmpty()) return task.getViewNames().get(0);
        if (task.getCustomSqlName() != null && !task.getCustomSqlName().isBlank()) return task.getCustomSqlName();
        return null;
    }

    /** 解析主键列名 — 用于全量分页 */
    private String resolvePkColumn(Long taskId) {
        Optional<SyncTask> taskOpt = syncTaskRepository.findById(taskId);
        if (taskOpt.isEmpty()) return null;
        SyncTask task = taskOpt.get();
        // upsertKeys 第一列优先
        if (task.getUpsertKeys() != null && !task.getUpsertKeys().isEmpty()) {
            return task.getUpsertKeys().get(0);
        }
        // splitPk 次选
        if (task.getSplitPk() != null && !task.getSplitPk().isBlank()) {
            return task.getSplitPk();
        }
        return null;
    }

    private Long resolveTargetDsId(Long taskId) {
        return syncTaskRepository.findById(taskId).map(SyncTask::getTargetDataSourceId).orElse(null);
    }

    private String truncateError(String msg) {
        if (msg == null) return null;
        return msg.length() > 2000 ? msg.substring(0, 2000) + "...(truncated)" : msg;
    }

    private void saveLog(Long taskId, Long batchId, String channel, String topic,
                         int messageCount, String status, String errorMessage,
                         String dataScope, Instant windowStart, Instant windowEnd,
                         String sampleMessages) {
        try {
            Long publishLogId = activePublishLogId.get();
            if (publishLogId != null) {
                if ("WAIT_RETRY".equals(status)) {
                    publishRunService.scheduleRetry(publishLogId, errorMessage);
                } else {
                    publishRunService.complete(
                            publishLogId, messageCount, status, errorMessage, sampleMessages);
                }
                return;
            }
            MessagePublishLog logEntry = new MessagePublishLog();
            logEntry.setTaskId(taskId);
            logEntry.setBatchId(batchId);
            logEntry.setChannel(channel);
            logEntry.setTopic(topic);
            logEntry.setMessageCount(messageCount);
            logEntry.setStatus(status);
            logEntry.setErrorMessage(errorMessage);
            logEntry.setPublishTime(Instant.now());
            logEntry.setDataScope(dataScope);
            logEntry.setWindowStart(windowStart);
            logEntry.setWindowEnd(windowEnd);
            logEntry.setSampleMessages(sampleMessages);
            logRepository.save(logEntry);
        } catch (Exception e) {
            log.error("MessagePublish: save log failed task={}, batch={}", taskId, batchId, e);
        }
    }

    /** 将样本列表序列化为 JSON 数组字符串；为空时返回 null */
    private String serializeSamples(List<String> samples) {
        if (samples == null || samples.isEmpty()) return null;
        try {
            return objectMapper.writeValueAsString(samples);
        } catch (Exception e) {
            log.warn("MessagePublish: serialize samples failed: {}", e.getMessage());
            return null;
        }
    }
}
