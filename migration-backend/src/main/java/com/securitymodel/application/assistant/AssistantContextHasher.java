package com.securitymodel.application.assistant;

import com.securitymodel.domain.task.PreDefenseTask;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/** Stable hashes for the preview/confirmation contract. */
public final class AssistantContextHasher {

    private AssistantContextHasher() {
    }

    public static String sha256(String value) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte item : hash) {
                hex.append(String.format("%02x", item));
            }
            return hex.toString();
        } catch (Exception exception) {
            throw new IllegalStateException("无法计算助手确认摘要", exception);
        }
    }

    public static String taskState(String operation, PreDefenseTask task, String extra) {
        return sha256(operation + "|" + task.getTaskId() + "|" + nullSafe(task.getStatus())
                + "|" + nullSafe(task.getCurrentRunId()) + "|" + nullSafe(task.getCurrentAttempt())
                + "|" + nullSafe(task.getCustomParams()) + "|" + nullSafe(task.getUpdateTime())
                + "|" + nullSafe(extra));
    }

    public static String createState(String canonicalRequest) {
        return sha256("create|" + canonicalRequest);
    }

    private static String nullSafe(Object value) {
        return value == null ? "" : String.valueOf(value);
    }
}
