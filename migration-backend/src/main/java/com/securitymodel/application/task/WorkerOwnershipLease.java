package com.securitymodel.application.task;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import jakarta.annotation.PreDestroy;
import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;

/**
 * Single-machine Worker ownership is a MySQL session lock, not a heartbeat row. The lock
 * lives on a dedicated connection: it disappears when that connection dies, and a second
 * Worker's {@code GET_LOCK(..., 0)} fails instead of touching another Worker's tasks.
 */
@Component
@ConditionalOnProperty(name = "security-model.dispatch.mode", havingValue = "rabbit")
public class WorkerOwnershipLease {

    private static final Logger log = LoggerFactory.getLogger(WorkerOwnershipLease.class);

    private final DataSource dataSource;
    private final DispatchRuntimePolicy runtimePolicy;
    private final String lockName;
    private Connection heldConnection;

    public WorkerOwnershipLease(
            DataSource dataSource,
            DispatchRuntimePolicy runtimePolicy,
            @Value("${security-model.dispatch.worker.ownership-lock-name:security_model_r4_worker_owner}")
            String lockName) {
        this.dataSource = dataSource;
        this.runtimePolicy = runtimePolicy;
        this.lockName = lockName;
    }

    public boolean ownershipRequired() {
        return runtimePolicy.runsConsumer();
    }

    public synchronized boolean acquire() {
        if (!ownershipRequired()) {
            return true;
        }
        if (heldConnection != null && isHeld()) {
            return true;
        }
        release();
        try {
            Connection connection = dataSource.getConnection();
            try (PreparedStatement statement = connection.prepareStatement("SELECT GET_LOCK(?, 0)")) {
                statement.setString(1, lockName);
                try (ResultSet resultSet = statement.executeQuery()) {
                    boolean acquired = resultSet.next() && resultSet.getInt(1) == 1;
                    if (acquired) {
                        heldConnection = connection;
                        log.info("Worker 归属租约已取得: lock={}", lockName);
                        return true;
                    }
                }
            }
            connection.close();
            log.warn("Worker 归属租约已被占用，拒绝启动消费: lock={}", lockName);
            return false;
        } catch (Exception exception) {
            log.warn("Worker 归属租约获取失败: lock={}, reason={}", lockName, exception.getMessage());
            return false;
        }
    }

    public synchronized boolean isHeld() {
        if (!ownershipRequired()) {
            return true;
        }
        if (heldConnection == null) {
            return false;
        }
        try (PreparedStatement statement = heldConnection.prepareStatement("SELECT 1");
             ResultSet resultSet = statement.executeQuery()) {
            return resultSet.next();
        } catch (Exception exception) {
            log.warn("Worker 归属连接已失效: lock={}, reason={}", lockName, exception.getMessage());
            return false;
        }
    }

    @PreDestroy
    public synchronized void release() {
        Connection connection = heldConnection;
        heldConnection = null;
        if (connection == null) {
            return;
        }
        try {
            try (PreparedStatement statement = connection.prepareStatement("SELECT RELEASE_LOCK(?)")) {
                statement.setString(1, lockName);
                statement.executeQuery();
            }
        } catch (Exception exception) {
            log.warn("Worker 归属租约释放失败: lock={}, reason={}", lockName, exception.getMessage());
        } finally {
            try {
                connection.close();
            } catch (Exception ignored) {
                // The lock is already released on the server when the session closes.
            }
        }
    }
}
