package com.securitymodel.application.task;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;

/** Reads the shared MySQL Worker ownership lease; an API process never owns it. */
@Component
public class WorkerOwnershipProbe {

    private final DataSource dataSource;
    private final DispatchRuntimePolicy runtimePolicy;
    private final String lockName;

    public WorkerOwnershipProbe(
            DataSource dataSource,
            DispatchRuntimePolicy runtimePolicy,
            @Value("${security-model.dispatch.worker.ownership-lock-name:security_model_r4_worker_owner}")
            String lockName) {
        this.dataSource = dataSource;
        this.runtimePolicy = runtimePolicy;
        this.lockName = lockName;
    }

    /** Returns the MySQL connection id holding the lock, or null when no Worker owns it. */
    public Long ownerConnectionId() {
        if (!runtimePolicy.rabbitMode()) {
            return null;
        }
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("SELECT IS_USED_LOCK(?)")) {
            statement.setString(1, lockName);
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next() || resultSet.getObject(1) == null) {
                    return null;
                }
                return resultSet.getLong(1);
            }
        } catch (Exception exception) {
            return null;
        }
    }
}
