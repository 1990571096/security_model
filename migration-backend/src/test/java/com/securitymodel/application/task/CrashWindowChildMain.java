package com.securitymodel.application.task;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;

/**
 * Test-only child JVM. It commits one crash-window state with auto-commit and then halts
 * without running Spring, recovery, or cleanup. The parent test observes the rows in MySQL
 * after this process is gone.
 */
public final class CrashWindowChildMain {

    private CrashWindowChildMain() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 6) {
            throw new IllegalArgumentException("url user password taskId intentStatus runtimeHash");
        }
        String url = args[0];
        String user = args[1];
        String password = args[2];
        long taskId = Long.parseLong(args[3]);
        String intentStatus = args[4];
        String runtimeHash = args[5];
        String runId = "run-crash-" + taskId;
        String requestJson = "{\"taskId\":" + taskId + ",\"attempt\":1,\"runId\":\"" + runId
                + "\",\"taskName\":\"crash-window-" + taskId
                + "\",\"stage\":\"pre\",\"createdBy\":\"crash-child\",\"customParams\":\"{}\","
                + "\"dataset\":{\"id\":1,\"name\":\"fixture-dataset\"},"
                + "\"algorithm\":{\"id\":1,\"name\":\"fixture-algorithm\"}}";
        String requestHash = sha256(requestJson);

        Class.forName("com.mysql.cj.jdbc.Driver");
        try (Connection connection = DriverManager.getConnection(url, user, password)) {
            connection.setAutoCommit(true);
            try (PreparedStatement task = connection.prepareStatement("""
                    INSERT INTO sys_pre_defense
                      (task_id, task_name, status, dataset_id, algorithm_id, progress,
                       current_run_id, current_attempt, create_by, create_time, update_time,
                       owner_user_id, data_origin)
                    VALUES (?, ?, 'running', 1, 1, 0, ?, 1, 'crash-child', NOW(), NOW(), 2, 'user')
                    """)) {
                task.setLong(1, taskId);
                task.setString(2, "crash-window-" + taskId);
                task.setString(3, runId);
                task.executeUpdate();
            }
            try (PreparedStatement intent = connection.prepareStatement("""
                    INSERT INTO sys_task_dispatch_intent
                      (task_id, run_id, attempt, status, schema_version, request_json, request_hash,
                       runtime_hash, created_at, updated_at)
                    VALUES (?, ?, 1, ?, 1, ?, ?, ?, NOW(), NOW())
                    """)) {
                intent.setLong(1, taskId);
                intent.setString(2, runId);
                intent.setString(3, intentStatus);
                intent.setString(4, requestJson);
                intent.setString(5, requestHash);
                intent.setString(6, runtimeHash);
                intent.executeUpdate();
            }
        }
        System.out.println("COMMITTED " + taskId + " " + intentStatus + " " + runId);
        System.out.flush();
        Thread.sleep(200);
        Runtime.getRuntime().halt(0);
    }

    private static String sha256(String value) throws Exception {
        byte[] hash = java.security.MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        StringBuilder hex = new StringBuilder();
        for (byte item : hash) {
            hex.append(String.format("%02x", item));
        }
        return hex.toString();
    }
}
