package com.dfygt.dfetl.server.service;

import com.dfygt.dfetl.server.service.publish.MessagePublishExecutionGuard;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.Set;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 有界并发执行队列。
 * <p>
 * 通过 Semaphore 限制最大同时执行的 ETL 任务数（默认 4）。
 * 超出执行槽上限时已入队任务会等待，超过队列容量时调用方快速失败。
 * Quartz Job 和手动触发都通过此类入队，执行由 DfetlExecutorService 完成。
 * <p>
 * 并发保护采用双层机制：
 * 1. 内存 Set（快速路径）：避免同一 JVM 内重复提交的 DB 开销
 * 2. PostgreSQL Advisory Lock（分布式互斥）：防止多实例或重启后重复执行
 */
@Component
@Slf4j
public class TaskExecutionQueue {

    private final Semaphore semaphore;
    private final Semaphore queueSemaphore;
    private final DfetlExecutorService executorService;
    private final DataSource dataSource;
    private final ShutdownState shutdownState;
    private final ExecutionCancellationRegistry cancellationRegistry;
    private final MessagePublishExecutionGuard messagePublishExecutionGuard;
    private final Set<Long> activeTaskIds = ConcurrentHashMap.newKeySet();
    private final Map<Long, Thread> workerThreads = new ConcurrentHashMap<>();

    // 停机相关字段
    private volatile boolean shuttingDown = false;
    private final Set<Thread> waitingThreads = ConcurrentHashMap.newKeySet();
    private final CountDownLatch drainLatch = new CountDownLatch(1);

    public TaskExecutionQueue(
            @Value("${dfetl.scheduler.max-concurrent:4}") int maxConcurrent,
            @Value("${dfetl.scheduler.queue-capacity:100}") int queueCapacity,
            DfetlExecutorService executorService,
            DataSource dataSource,
            ShutdownState shutdownState,
            ExecutionCancellationRegistry cancellationRegistry,
            MessagePublishExecutionGuard messagePublishExecutionGuard) {
        if (maxConcurrent < 1) {
            throw new IllegalArgumentException("dfetl.scheduler.max-concurrent 必须大于 0");
        }
        if (queueCapacity < 1) {
            throw new IllegalArgumentException("dfetl.scheduler.queue-capacity 必须大于 0");
        }
        this.semaphore = new Semaphore(maxConcurrent, true);
        this.queueSemaphore = new Semaphore(queueCapacity, true);
        this.executorService = executorService;
        this.dataSource = dataSource;
        this.shutdownState = shutdownState;
        this.cancellationRegistry = cancellationRegistry;
        this.messagePublishExecutionGuard = messagePublishExecutionGuard;
        log.info("TaskExecutionQueue initialized: maxConcurrent={} queueCapacity={}", maxConcurrent, queueCapacity);
    }

    /**
     * 提交一个任务到执行队列。
     * <p>
     * 非阻塞：立即获取许可（如果有空槽），否则在虚拟线程中等待。
     * 执行完成后自动释放许可。
     * <p>
     * 并发保护：内存 Set 快速路径 + PostgreSQL Advisory Lock 分布式互斥。
     *
     * @param taskId      同步任务 ID
     * @param triggeredBy 触发来源，如 "MANUAL" / "SCHEDULER"
     */
    public void submit(Long taskId, String triggeredBy) {
        try (SubmissionReservation reservation = reserve(taskId)) {
            reservation.submit(triggeredBy);
        }
    }

    /**
     * 在调用方执行破坏性前置动作前预留队列容量和 taskId。
     * 未调用 {@link SubmissionReservation#submit(String)} 时，close 会自动释放预留。
     */
    public SubmissionReservation reserve(Long taskId) {
        reserveIdentity(taskId);
        return new SubmissionReservation(this, taskId, false, null, false);
    }

    /**
     * 为重采等破坏性操作同步预占队列容量、实际执行槽和跨实例 advisory lock。
     * 任一资源不可用时立即失败，调用方不得进入预检或目标表清理。
     */
    public SubmissionReservation reserveDestructive(Long taskId) {
        reserveIdentity(taskId);
        if (!semaphore.tryAcquire()) {
            releaseIdentity(taskId);
            throw new IllegalStateException("执行槽已满，拒绝破坏性操作 taskId=" + taskId);
        }

        Connection lockConnection = null;
        boolean advisoryLockAcquired = false;
        try {
            lockConnection = dataSource.getConnection();
            try (PreparedStatement ps =
                         lockConnection.prepareStatement("SELECT pg_try_advisory_lock(?)")) {
                ps.setLong(1, taskId);
                try (ResultSet rs = ps.executeQuery()) {
                    advisoryLockAcquired = rs.next() && rs.getBoolean(1);
                }
            }
            if (!advisoryLockAcquired) {
                throw new IllegalStateException(
                        "任务已在其他实例执行中，拒绝破坏性操作 taskId=" + taskId);
            }
            messagePublishExecutionGuard.assertCanStart(taskId);
            return new SubmissionReservation(
                    this, taskId, true, lockConnection, true);
        } catch (RuntimeException e) {
            releaseReservedExecution(taskId, lockConnection, advisoryLockAcquired, true);
            throw e;
        } catch (Exception e) {
            releaseReservedExecution(taskId, lockConnection, advisoryLockAcquired, true);
            throw new IllegalStateException(
                    "破坏性操作预占失败 taskId=" + taskId + ": " + e.getMessage(), e);
        }
    }

    private void reserveIdentity(Long taskId) {
        // 停机检查：快速失败，不阻塞调用线程
        if (shuttingDown) {
            throw new IllegalStateException("系统正在停机中，拒绝新任务提交 taskId=" + taskId);
        }

        if (!queueSemaphore.tryAcquire()) {
            throw new IllegalStateException("执行队列已满，拒绝新任务提交 taskId=" + taskId);
        }

        // 快速路径：内存 Set 去重，避免同一 JVM 内重复提交的 DB 开销
        if (!activeTaskIds.add(taskId)) {
            queueSemaphore.release();
            throw new IllegalStateException("任务已在排队或执行中");
        }
    }

    private void releaseIdentity(Long taskId) {
        activeTaskIds.remove(taskId);
        queueSemaphore.release();
    }

    private void releaseReservedExecution(
            Long taskId, Connection lockConnection, boolean advisoryLockAcquired,
            boolean slotAcquired) {
        releaseExecutionResources(taskId, lockConnection, advisoryLockAcquired, slotAcquired);
        releaseIdentity(taskId);
    }

    public static class SubmissionReservation implements AutoCloseable {
        private final TaskExecutionQueue owner;
        private final Long taskId;
        private final boolean executionSlotReserved;
        private final Connection lockConnection;
        private final boolean advisoryLockReserved;
        private final AtomicBoolean submitted = new AtomicBoolean(false);
        private final AtomicBoolean closed = new AtomicBoolean(false);

        SubmissionReservation(
                TaskExecutionQueue owner, Long taskId, boolean executionSlotReserved,
                Connection lockConnection, boolean advisoryLockReserved) {
            this.owner = owner;
            this.taskId = taskId;
            this.executionSlotReserved = executionSlotReserved;
            this.lockConnection = lockConnection;
            this.advisoryLockReserved = advisoryLockReserved;
        }

        public void submit(String triggeredBy) {
            if (closed.get()) {
                throw new IllegalStateException("任务提交预留已释放 taskId=" + taskId);
            }
            if (!submitted.compareAndSet(false, true)) {
                throw new IllegalStateException("任务提交预留已使用 taskId=" + taskId);
            }
            try {
                owner.startWorker(taskId, triggeredBy, this);
            } catch (RuntimeException e) {
                submitted.set(false);
                close();
                throw e;
            }
        }

        @Override
        public void close() {
            if (closed.compareAndSet(false, true) && !submitted.get()) {
                owner.releaseReservedExecution(
                        taskId, lockConnection, advisoryLockReserved, executionSlotReserved);
            }
        }
    }

    private void startWorker(
            Long taskId, String triggeredBy, SubmissionReservation reservation) {
        Thread.ofVirtual()
                .name("exec-queue-" + taskId)
                .start(() -> {
                    Thread currentThread = Thread.currentThread();
                    workerThreads.put(taskId, currentThread);
                    Connection lockConn = reservation.lockConnection;
                    boolean slotAcquired = reservation.executionSlotReserved;
                    boolean advisoryLockAcquired = reservation.advisoryLockReserved;
                    try {
                        // 先等待 JVM 内执行槽，再占用业务连接池连接。
                        // 否则大量排队任务会各自持有 advisory-lock 连接，耗尽 Hikari 连接池，
                        // 导致真正运行中的任务无法通过 JPA 获取连接。
                        if (!slotAcquired) {
                            log.info("TaskExecutionQueue: waiting for slot, taskId={} triggeredBy={}",
                                    taskId, triggeredBy);
                            waitingThreads.add(currentThread);
                            try {
                                semaphore.acquire();
                                slotAcquired = true;
                            } finally {
                                waitingThreads.remove(currentThread);
                            }
                        }
                        log.info("TaskExecutionQueue: slot acquired, taskId={}", taskId);

                        if (cancellationRegistry.isRequested(taskId)) {
                            log.info("TaskExecutionQueue: skip cancelled queued taskId={}", taskId);
                            return;
                        }

                        if (!advisoryLockAcquired) {
                            lockConn = dataSource.getConnection();
                            try (PreparedStatement ps =
                                         lockConn.prepareStatement("SELECT pg_try_advisory_lock(?)")) {
                                ps.setLong(1, taskId);
                                try (ResultSet rs = ps.executeQuery()) {
                                    if (!rs.next() || !rs.getBoolean(1)) {
                                        log.warn("TaskExecutionQueue: 任务已在其他实例执行中, taskId={}",
                                                taskId);
                                        return;
                                    }
                                }
                            }
                            advisoryLockAcquired = true;
                        }
                        log.info("TaskExecutionQueue: advisory lock acquired, taskId={}", taskId);

                        messagePublishExecutionGuard.assertCanStart(taskId);
                        executorService.runAsync(taskId, triggeredBy).join();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        log.info("TaskExecutionQueue: task {} cancelled due to shutdown", taskId);
                    } catch (IllegalStateException e) {
                        log.warn("TaskExecutionQueue: {}, taskId={}", e.getMessage(), taskId);
                    } catch (Exception e) {
                        log.error("TaskExecutionQueue: unexpected error, taskId={}", taskId, e);
                    } finally {
                        releaseReservedExecution(
                                taskId, lockConn, advisoryLockAcquired, slotAcquired);
                        workerThreads.remove(taskId);
                        cancellationRegistry.clear(taskId);
                        waitingThreads.remove(currentThread); // ensure cleanup even if not removed earlier
                        if (shuttingDown && activeTaskIds.isEmpty()) {
                            drainLatch.countDown();
                        }
                    }
                });
    }

    private void releaseExecutionResources(
            Long taskId, Connection lockConnection, boolean advisoryLockAcquired,
            boolean slotAcquired) {
        if (lockConnection != null) {
            if (advisoryLockAcquired) {
                try (PreparedStatement ps =
                             lockConnection.prepareStatement("SELECT pg_advisory_unlock(?)")) {
                    ps.setLong(1, taskId);
                    ps.executeQuery();
                    log.debug("TaskExecutionQueue: advisory lock released, taskId={}", taskId);
                } catch (Exception ignored) {
                    log.debug("TaskExecutionQueue: failed to release advisory lock "
                            + "(will auto-release on connection close), taskId={}", taskId);
                }
            }
            try {
                lockConnection.close();
            } catch (Exception ignored) {
                log.debug("TaskExecutionQueue: failed to close lock connection, taskId={}", taskId);
            }
        }
        if (slotAcquired) {
            semaphore.release();
            log.info("TaskExecutionQueue: slot released, taskId={}", taskId);
        }
    }

    /** 当前等待中的任务数（Semaphore 队列长度） */
    public int getQueueLength() {
        return semaphore.getQueueLength();
    }

    /** 当前可用并发槽数 */
    public int getAvailableSlots() {
        return semaphore.availablePermits();
    }

    /**
     * 进入停机模式：
     * 1. 设置 shutdown 标志，后续 submit() 调用将被拒绝
     * 2. 中断所有正在等待 semaphore.acquire() 的虚拟线程
     */
    public void initiateShutdown() {
        this.shuttingDown = true;
        this.shutdownState.markShuttingDown();
        for (Thread t : waitingThreads) {
            t.interrupt();
        }
        log.info("TaskExecutionQueue: shutdown initiated, interrupted {} waiting threads",
                waitingThreads.size());
    }

    /**
     * 等待所有在途任务完成。
     *
     * @param timeout 最大等待时长
     * @param unit    时间单位
     * @return true 如果所有任务在超时前完成，false 如果超时
     */
    public boolean awaitDrain(long timeout, TimeUnit unit) {
        if (activeTaskIds.isEmpty()) {
            return true;
        }
        try {
            return drainLatch.await(timeout, unit);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /** 当前活跃任务数（排队中 + 执行中） */
    public int getActiveCount() {
        return activeTaskIds.size();
    }

    /**
     * 请求取消排队中或本 JVM 正在执行的任务。
     *
     * <p>等待 semaphore 的 worker 会被立即中断；已经进入执行 future 的任务由
     * {@link DfetlExecutorService} 读取同一 registry，阻止 retry、水位和成功后动作。
     */
    public boolean cancelTask(Long taskId) {
        if (taskId == null || !activeTaskIds.contains(taskId)) {
            return false;
        }
        Thread worker = workerThreads.get(taskId);
        if (worker == null) {
            // destructive reservation 可能仍在预检/清理前阶段；此时记录取消会导致清表后
            // worker 直接跳过执行，留下空目标表。只接受已启动 worker 的取消请求。
            return false;
        }
        cancellationRegistry.request(taskId);
        worker.interrupt();
        return true;
    }

    /** 当前活跃任务 ID 集合（快照） */
    public Set<Long> getActiveTaskIds() {
        return Set.copyOf(activeTaskIds);
    }

    /** 是否正在停机中 */
    public boolean isShuttingDown() {
        return shuttingDown;
    }
}
