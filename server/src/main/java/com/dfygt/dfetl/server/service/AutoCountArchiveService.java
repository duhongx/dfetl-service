package com.dfygt.dfetl.server.service;

import com.dfygt.dfetl.server.entity.ValidationRun;
import com.dfygt.dfetl.server.repository.ValidationRunRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * spec validation-workbench-redesign · Task P1-9.3
 * Validates: Requirement 5 (AC 4) + Property 10
 *
 * <p>AUTO_COUNT 归档：每日 03:00 触发，删除 {@code validation_run} 表中
 * {@code trigger_type='AUTO_COUNT'} 且 {@code created_at < now() - 7 days} 的记录。
 *
 * <p>仅删 AUTO_COUNT 类型；CHECKSUM / DRIFT / GATE / MANUAL 历史不受影响。
 *
 * <p>批量 DELETE，每批 1000 条，避免单次大事务。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AutoCountArchiveService {

    private final ValidationRunRepository repository;

    /** 默认归档保留天数（可通过 application.yml 覆盖） */
    @Value("${dfetl.validation.auto-count-retention-days:7}")
    private int retentionDays;

    /** 单批删除上限，避免单次事务过大锁定 PG。 */
    private static final int BATCH_SIZE = 1000;

    /**
     * Quartz Cron 每日 03:00 触发归档。
     *
     * <p>使用 Spring {@code @Scheduled(cron=...)}（基于 ThreadPoolTaskScheduler 内存调度），
     * 与项目其他 Quartz 持久化任务（如 driftCron / scheduleSnapshotDetect）独立。归档失败不阻塞。
     */
    @Scheduled(cron = "0 0 3 * * ?", zone = "Asia/Shanghai")
    public void archiveScheduled() {
        try {
            int deleted = archiveOlderThan(retentionDays);
            log.info("AutoCountArchiveService: 完成归档 retentionDays={} deleted={}", retentionDays, deleted);
        } catch (Exception e) {
            log.warn("AutoCountArchiveService: 归档异常 {}", e.getMessage(), e);
        }
    }

    /**
     * 删除超过 retentionDays 天的 AUTO_COUNT 记录。批量删除避免大事务。
     *
     * @return 实际删除行数
     */
    @Transactional
    public int archiveOlderThan(int retentionDays) {
        LocalDateTime cutoff = LocalDateTime.now().minusDays(retentionDays);
        int totalDeleted = 0;
        while (true) {
            // 用 Pageable.first(BATCH_SIZE) 取一批 id
            List<Long> batch = repository.findIdsForAutoCountArchive(cutoff,
                    org.springframework.data.domain.PageRequest.of(0, BATCH_SIZE));
            if (batch.isEmpty()) break;
            int deleted = repository.deleteByIds(batch);
            totalDeleted += deleted;
            if (batch.size() < BATCH_SIZE) break;
        }
        return totalDeleted;
    }
}
