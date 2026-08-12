package com.dfygt.dfetl.server.scheduler.controller;

import com.dfygt.dfetl.server.common.ApiResponse;
import com.dfygt.dfetl.server.scheduler.dto.CronPreviewRequest;
import com.dfygt.dfetl.server.scheduler.dto.CronPreviewResponse;
import com.dfygt.dfetl.server.scheduler.dto.ScheduleConfigPreviewRequest;
import com.dfygt.dfetl.server.scheduler.model.ScheduleConfig;
import com.dfygt.dfetl.server.scheduler.model.ScheduleMode;
import com.dfygt.dfetl.server.scheduler.service.CronExpressionService;
import com.dfygt.dfetl.server.scheduler.service.ScheduleConfigService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * spec 053 - Cron 预览接口（含可视化 ScheduleConfig 预览）。
 */
@RestController
@RequestMapping("/api/scheduler")
@RequiredArgsConstructor
public class SchedulerController {

    private final CronExpressionService cronExpressionService;
    private final ScheduleConfigService scheduleConfigService;

    /**
     * 直接对 cronExpression 做预览（高级模式或回填使用）。
     */
    @PostMapping("/cron/preview")
    public ApiResponse<CronPreviewResponse> previewCron(@RequestBody CronPreviewRequest req) {
        CronPreviewResponse resp = new CronPreviewResponse();
        resp.setCronExpression(req.getCronExpression());
        resp.setTimezone(req.getTimezone() == null ? CronExpressionService.DEFAULT_TIMEZONE : req.getTimezone());
        boolean valid = cronExpressionService.isValid(req.getCronExpression());
        resp.setValid(valid);
        if (!valid) {
            resp.getWarnings().add("Cron 表达式格式不正确，请使用 Quartz 6/7 段表达式");
            return ApiResponse.ok(resp);
        }
        int count = req.getCount() == null ? 5 : req.getCount();
        resp.setNextRuns(cronExpressionService.getNextRuns(req.getCronExpression(), resp.getTimezone(), count));
        resp.getWarnings().addAll(cronExpressionService.detectRisks(req.getCronExpression()));
        // 反解析为 scheduleConfig，方便前端回显
        resp.setScheduleConfig(scheduleConfigService.fromCron(req.getCronExpression(), resp.getTimezone()));
        resp.setDescription(resp.getScheduleConfig() == null ? null : resp.getScheduleConfig().getDescription());
        return ApiResponse.ok(resp);
    }

    /**
     * 根据可视化配置生成 cron + 中文描述 + 未来执行时间。
     */
    @PostMapping("/schedule-config/preview")
    public ApiResponse<CronPreviewResponse> previewScheduleConfig(@RequestBody ScheduleConfigPreviewRequest req) {
        CronPreviewResponse resp = new CronPreviewResponse();
        String tz = req.getTimezone() == null ? CronExpressionService.DEFAULT_TIMEZONE : req.getTimezone();
        resp.setTimezone(tz);

        ScheduleConfig config = new ScheduleConfig();
        config.setMode(req.getMode());
        config.setTimezone(tz);
        config.setIntervalMinutes(req.getIntervalMinutes());
        config.setIntervalHours(req.getIntervalHours());
        config.setHour(req.getHour());
        config.setMinute(req.getMinute());
        config.setDayOfMonth(req.getDayOfMonth());
        config.setDaysOfWeek(req.getDaysOfWeek());
        config.setCronExpression(req.getCronExpression());
        config.setVersion(1);

        try {
            String cron = scheduleConfigService.toCron(config);
            config.setCronExpression(cron);
            config.setDescription(scheduleConfigService.describe(config));
            resp.setValid(true);
            resp.setCronExpression(cron);
            resp.setDescription(config.getDescription());
            resp.setScheduleConfig(config);
            int count = req.getCount() == null ? 5 : req.getCount();
            if (config.getMode() != ScheduleMode.MANUAL) {
                resp.setNextRuns(cronExpressionService.getNextRuns(cron, tz, count));
                resp.getWarnings().addAll(cronExpressionService.detectRisks(cron));
            }
        } catch (IllegalArgumentException e) {
            resp.setValid(false);
            resp.getWarnings().add(e.getMessage());
        }
        return ApiResponse.ok(resp);
    }
}
