package com.dfygt.dfetl.server.medical.precheck;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;

/** 使用独立命名空间的 PostgreSQL session advisory lock 防止多实例重复推进同一 run。 */
@Component
@Slf4j
public class PrecheckRunLock {

    private static final long LOCK_NAMESPACE = 0x50524543484B0000L;

    private final DataSource dataSource;

    public PrecheckRunLock(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    public boolean runIfAcquired(Long runId, Runnable action) {
        if (runId == null || runId <= 0) {
            throw new IllegalArgumentException("runId 必须为正整数");
        }
        if (action == null) {
            throw new IllegalArgumentException("action 不能为空");
        }
        long lockKey = LOCK_NAMESPACE ^ runId;
        try (Connection connection = dataSource.getConnection()) {
            boolean acquired = tryAcquire(connection, lockKey);
            if (!acquired) {
                return false;
            }
            try {
                action.run();
                return true;
            } finally {
                unlock(connection, lockKey, runId);
            }
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("获取数据预检跨实例锁失败: runId=" + runId, e);
        }
    }

    private boolean tryAcquire(Connection connection, long lockKey) throws Exception {
        try (PreparedStatement lockStatement =
                     connection.prepareStatement("SELECT pg_try_advisory_lock(?)")) {
            lockStatement.setLong(1, lockKey);
            try (ResultSet resultSet = lockStatement.executeQuery()) {
                return resultSet.next() && resultSet.getBoolean(1);
            }
        }
    }

    private void unlock(Connection connection, long lockKey, Long runId) {
        try (PreparedStatement statement =
                     connection.prepareStatement("SELECT pg_advisory_unlock(?)")) {
            statement.setLong(1, lockKey);
            try (ResultSet ignored = statement.executeQuery()) {
                // PostgreSQL session lock 只需执行解锁函数；返回值不改变本次业务结果。
            }
        } catch (Exception e) {
            log.warn("释放数据预检跨实例锁失败: runId={}", runId, e);
        }
    }
}
