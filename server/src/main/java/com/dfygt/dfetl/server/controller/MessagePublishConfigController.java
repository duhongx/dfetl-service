package com.dfygt.dfetl.server.controller;

import com.dfygt.dfetl.server.common.ApiResponse;
import com.dfygt.dfetl.server.config.MessagePublishProperties;
import com.dfygt.dfetl.server.dto.MessagePublishConfigDto;
import com.dfygt.dfetl.server.dto.MessagePublishLogDto;
import com.dfygt.dfetl.server.service.publish.MessagePublishConfigService;
import com.dfygt.dfetl.server.service.publish.MessagePublishTrigger;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

/**
 * 消息发布配置 REST API — 配置 CRUD、重发触发、发布日志查询。
 * <p>
 * 实现层级：Controller → {@link MessagePublishConfigService} 处理持久化与 DTO 转换；
 * 重发动作直接委托 {@link MessagePublishTrigger}。
 */
@RestController
@RequestMapping("/api/message-publish")
@RequiredArgsConstructor
public class MessagePublishConfigController {

    private final MessagePublishConfigService configService;
    private final MessagePublishTrigger publishTrigger;
    private final org.springframework.data.redis.core.StringRedisTemplate stringRedisTemplate;
    private final MessagePublishProperties messagePublishProperties;

    // ── 传输方式查询 ─────────────────────────────────────────────────────────

    /**
     * 返回当前系统配置的消息传输方式。
     */
    @GetMapping("/transport")
    public ApiResponse<Map<String, String>> getTransport() {
        return ApiResponse.ok(Map.of("transport", messagePublishProperties.getTransport().name()));
    }

    // ── 配置 CRUD ────────────────────────────────────────────────────────────

    @GetMapping("/config/{taskId}")
    public ApiResponse<MessagePublishConfigDto> getConfig(@PathVariable Long taskId) {
        MessagePublishConfigDto dto = configService.getConfig(taskId)
                .orElseThrow(() -> new NoSuchElementException(
                        "MessagePublishConfig not found for taskId=" + taskId));
        return ApiResponse.ok(dto);
    }

    @PostMapping("/config")
    public ApiResponse<MessagePublishConfigDto> createConfig(
            @RequestBody @Valid MessagePublishConfigDto dto) {
        return ApiResponse.ok(configService.createConfig(dto));
    }

    @PutMapping("/config/{taskId}")
    public ApiResponse<MessagePublishConfigDto> updateConfig(
            @PathVariable Long taskId,
            @RequestBody @Valid MessagePublishConfigDto dto) {
        return ApiResponse.ok(configService.updateConfig(taskId, dto));
    }

    @DeleteMapping("/config/{taskId}")
    public ApiResponse<Void> deleteConfig(@PathVariable Long taskId) {
        configService.deleteConfig(taskId);
        return ApiResponse.ok();
    }

    // ── 重发 ─────────────────────────────────────────────────────────────────

    /**
     * 按日志 ID 重发某次发布。
     * 从日志记录中反查 taskId/batchId/window，前端只需传 logId。
     */
    @PostMapping(value = "/republish/log/{logId}", params = "!mode")
    public ApiResponse<Void> republishByLogId(@PathVariable Long logId) {
        publishTrigger.republishByLogId(logId);
        return ApiResponse.ok();
    }

    /**
     * 按时间窗口重发：[start, end) 范围内的增量数据重新发布。
     */
    @PostMapping("/republish/time-window")
    public ApiResponse<Void> republishByTimeWindow(
            @RequestParam Long taskId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant start,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant end) {
        publishTrigger.republishByTimeWindow(taskId, start, end);
        return ApiResponse.ok();
    }

    // ── 日志查询 ─────────────────────────────────────────────────────────────

    @GetMapping("/logs/{taskId}")
    public ApiResponse<Page<MessagePublishLogDto>> getLogs(
            @PathVariable Long taskId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        Pageable pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "publishTime"));
        return ApiResponse.ok(configService.getLogs(taskId, pageable));
    }

    // ── Redis Stream 预览 ────────────────────────────────────────────────────

    /**
     * 获取某次发布的消息样本（前5条完整消息JSON）。
     */
    @GetMapping("/logs/{logId}/messages")
    public ApiResponse<List<Map<String, Object>>> getLogMessages(@PathVariable Long logId) {
        var logOpt = configService.getLogById(logId);
        if (logOpt.isEmpty()) {
            throw new NoSuchElementException("PublishLog not found: " + logId);
        }
        String sampleJson = logOpt.get().getSampleMessages();
        if (sampleJson == null || sampleJson.isBlank()) {
            return ApiResponse.ok(List.of());
        }
        try {
            ObjectMapper mapper = new ObjectMapper();
            // sampleMessages 存储为 JSON 数组，元素可能是字符串（序列化的 JSON）或直接对象
            List<Object> rawList = mapper.readValue(sampleJson, new TypeReference<>() {});
            List<Map<String, Object>> messages = new java.util.ArrayList<>();
            for (Object item : rawList) {
                if (item instanceof String str) {
                    // 每个元素是 JSON 字符串，需要二次解析
                    Map<String, Object> parsed = mapper.readValue(str, new TypeReference<>() {});
                    messages.add(parsed);
                } else if (item instanceof Map<?, ?> map) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> typed = (Map<String, Object>) map;
                    messages.add(typed);
                }
            }
            return ApiResponse.ok(messages);
        } catch (Exception e) {
            return ApiResponse.ok(List.of());
        }
    }

    /**
     * 预览 Redis Stream 中的最新消息（从尾部读取最近 N 条）。
     */
    @GetMapping("/stream-preview/{taskId}")
    public ApiResponse<Map<String, Object>> streamPreview(
            @PathVariable Long taskId,
            @RequestParam(defaultValue = "10") int count) {
        var configOpt = configService.getConfig(taskId);
        if (configOpt.isEmpty()) {
            return ApiResponse.ok(Map.of("streamKey", "", "total", 0, "messages", List.of()));
        }
        String topic = configOpt.get().getTopic();
        String streamKey = topic;
        int safeCount = Math.min(Math.max(1, count), 50);

        Long total = stringRedisTemplate.opsForStream().size(streamKey);
        if (total == null) total = 0L;

        // XREVRANGE: 从最新到最旧读取
        var records = stringRedisTemplate.opsForStream().reverseRange(streamKey,
                org.springframework.data.domain.Range.unbounded(),
                org.springframework.data.redis.connection.Limit.limit().count(safeCount));

        List<Map<String, Object>> messages = new java.util.ArrayList<>();
        if (records != null) {
            for (var record : records) {
                Map<String, Object> msg = new java.util.LinkedHashMap<>();
                msg.put("id", record.getId().toString());
                // 新格式：多字段 hash，组装为单个 JSON 字符串展示
                Map<Object, Object> rawFields = record.getValue();
                Map<String, String> fields = new java.util.LinkedHashMap<>();
                for (var entry : rawFields.entrySet()) {
                    fields.put(String.valueOf(entry.getKey()),
                            entry.getValue() != null ? String.valueOf(entry.getValue()) : "");
                }
                msg.put("data", fields);
                messages.add(msg);
            }
        }

        return ApiResponse.ok(Map.of(
                "streamKey", streamKey,
                "total", total,
                "messages", messages
        ));
    }
}
