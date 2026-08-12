package com.dfygt.dfetl.server.config.retry;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * SeaTunnel REST 调用的内存级断路器。
 * 状态机：CLOSED → OPEN（连续失败 >= threshold）→ HALF_OPEN（冷却后）→ CLOSED（试探成功）
 */
@Component
@Slf4j
public class SeaTunnelCircuitBreaker {

    public enum State { CLOSED, OPEN, HALF_OPEN }

    private final int threshold;
    private final long cooldownMs;
    private Clock clock;

    private volatile State state = State.CLOSED;
    private final AtomicInteger consecutiveFailures = new AtomicInteger(0);
    private volatile long lastFailureTimestamp = 0;

    public SeaTunnelCircuitBreaker(RetryConfigProperties props) {
        this.threshold = props.getSeatunnel().getCircuitBreakerThreshold();
        this.cooldownMs = props.getSeatunnel().getCircuitBreakerCooldownMs();
        this.clock = Clock.systemDefaultZone();
    }

    /**
     * 包内可见的 Clock 设置方法，用于测试中注入可控时钟。
     */
    void setClock(Clock clock) {
        this.clock = clock;
    }

    /**
     * 是否允许请求通过。
     * CLOSED/HALF_OPEN → true；OPEN 且未过冷却 → false；OPEN 且已过冷却 → 转 HALF_OPEN 并允许
     */
    public synchronized boolean allowRequest() {
        return switch (state) {
            case CLOSED -> true;
            case HALF_OPEN -> true;
            case OPEN -> {
                long elapsed = clock.millis() - lastFailureTimestamp;
                if (elapsed >= cooldownMs) {
                    State prev = state;
                    state = State.HALF_OPEN;
                    log.warn("[Retry:SeaTunnel:CircuitBreaker] {} -> HALF_OPEN, cooldown elapsed ({}ms)",
                            prev, elapsed);
                    yield true;
                }
                yield false;
            }
        };
    }

    /** 记录成功调用 */
    public synchronized void recordSuccess() {
        if (state == State.HALF_OPEN) {
            State prev = state;
            state = State.CLOSED;
            consecutiveFailures.set(0);
            log.warn("[Retry:SeaTunnel:CircuitBreaker] {} -> CLOSED, probe succeeded", prev);
        } else if (state == State.CLOSED) {
            consecutiveFailures.set(0);
        }
    }

    /** 记录失败调用 */
    public synchronized void recordFailure() {
        int failures = consecutiveFailures.incrementAndGet();
        lastFailureTimestamp = clock.millis();

        if (state == State.HALF_OPEN) {
            State prev = state;
            state = State.OPEN;
            log.warn("[Retry:SeaTunnel:CircuitBreaker] {} -> OPEN, probe failed, failures={}",
                    prev, failures);
        } else if (state == State.CLOSED && failures >= threshold) {
            State prev = state;
            state = State.OPEN;
            log.warn("[Retry:SeaTunnel:CircuitBreaker] {} -> OPEN, failures={} >= threshold={}",
                    prev, failures, threshold);
        }
    }

    public State getState() {
        return state;
    }

    public int getConsecutiveFailures() {
        return consecutiveFailures.get();
    }
}
