package com.dfygt.dfetl.server.service;

import com.dfygt.dfetl.server.entity.SyncTask;
import com.dfygt.dfetl.server.entity.TaskValidationConfig;
import com.dfygt.dfetl.server.repository.SyncTaskRepository;
import com.dfygt.dfetl.server.repository.TaskValidationConfigRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Spec 030：drift-watch 周期校验执行器。
 * <p>
 * 每次被 Quartz 触发时：
 * <ol>
 *   <li>加载 SyncTask 与 TaskValidationConfig</li>
 *   <li>config.enabled=false 或 driftCron 已被清空 → 跳过（自我兜底）</li>
 *   <li>创建 ValidationRun trigger_type='DRIFT'，method=config.method 兜底 ROW_COUNT</li>
 *   <li>调用 ValidationRunner.runAsync 异步执行，不阻塞 Quartz 线程</li>
 * </ol>
 * <p>异常一律 swallow + log，避免 Quartz misfire。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DriftWatchService {

    private final SyncTaskRepository syncTaskRepo;
    private final TaskValidationConfigRepository configRepo;
    private final ValidationDispatchService dispatchService;
    private final EffectiveValidationMethodResolver methodResolver;

    public void runDriftCheck(Long taskId) {
        try {
            SyncTask task = syncTaskRepo.findById(taskId).orElse(null);
            if (task == null) {
                log.warn("DriftWatchService: SyncTask not found taskId={}", taskId);
                return;
            }
            TaskValidationConfig config = configRepo.findByTaskId(taskId).orElse(null);
            if (config == null || !Boolean.TRUE.equals(config.getEnabled())
                    || config.getDriftCron() == null || config.getDriftCron().isBlank()) {
                log.debug("DriftWatchService: skip taskId={} (config 缺失或已禁用)", taskId);
                return;
            }

            String method = methodResolver.resolveTriggeredMethod(task, config);
            WatermarkService.WindowContext fullWindow = new WatermarkService.WindowContext("FULL", null, null, null, null);
            dispatchService.dispatchTriggered(task, null, "DRIFT", method, fullWindow, config);
        } catch (Exception e) {
            log.warn("DriftWatchService: drift check failed taskId={}: {}", taskId, e.getMessage(), e);
        }
    }
}
