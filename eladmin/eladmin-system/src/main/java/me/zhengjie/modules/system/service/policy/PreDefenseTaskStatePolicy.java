package me.zhengjie.modules.system.service.policy;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * Centralizes the lifecycle rules for a pre-defense task.
 *
 * Keeping these rules outside the controller and mapper makes the state
 * machine explicit and gives it a small, deterministic unit-test surface.
 */
public final class PreDefenseTaskStatePolicy {

    public static final String PENDING = "pending";
    public static final String RUNNING = "running";
    public static final String SUCCESS = "success";
    public static final String FAILED = "failed";
    public static final String CANCELED = "canceled";

    private static final Set<String> SUPPORTED_STATUSES = Collections.unmodifiableSet(
            new HashSet<>(Arrays.asList(PENDING, RUNNING, SUCCESS, FAILED, CANCELED)));

    private PreDefenseTaskStatePolicy() {
    }

    public static boolean isSupportedStatus(String status) {
        return status != null && SUPPORTED_STATUSES.contains(status);
    }

    public static boolean isTerminal(String status) {
        return SUCCESS.equals(status) || FAILED.equals(status) || CANCELED.equals(status);
    }

    public static boolean isStartable(String status) {
        return PENDING.equals(status);
    }

    public static boolean isRetryable(String status) {
        return FAILED.equals(status);
    }

    public static boolean isValidTransition(String current, String next) {
        if (!isSupportedStatus(current) || !isSupportedStatus(next)) {
            return false;
        }
        if (current.equals(next)) {
            return RUNNING.equals(current);
        }
        return PENDING.equals(current) && RUNNING.equals(next)
                || RUNNING.equals(current) && (
                SUCCESS.equals(next) || FAILED.equals(next) || CANCELED.equals(next));
    }

    public static boolean isValidProgress(Integer progress) {
        return progress != null && progress >= 0 && progress <= 100;
    }
}
