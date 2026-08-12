package com.dfygt.dfetl.server.service;

import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 全局停机状态标志（共享单例）。
 * <p>
 * 用于解耦 {@link TaskExecutionQueue} 与 {@link DfetlExecutorService} 之间的循环依赖：
 * {@code TaskExecutionQueue} 构造时依赖 {@code DfetlExecutorService}，因此后者不能反向注入
 * {@code TaskExecutionQueue}。两者改为共享本 bean 读写停机标志。
 * <p>
 * 由 {@link ShutdownCoordinator}/{@link TaskExecutionQueue} 在进入优雅停机时置位，
 * {@code DfetlExecutorService} 的自动重试循环据此感知停机、停止拉起新的远程作业。
 */
@Component
public class ShutdownState {

    private final AtomicBoolean shuttingDown = new AtomicBoolean(false);

    /** 标记进入停机模式。 */
    public void markShuttingDown() {
        shuttingDown.set(true);
    }

    /** 是否正在停机中。 */
    public boolean isShuttingDown() {
        return shuttingDown.get();
    }
}
