package me.zhengjie.modules.system.service.policy;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PreDefenseTaskStatePolicyTest {

    @Test
    void allowsOnlyTheExpectedLifecycleTransitions() {
        assertTrue(PreDefenseTaskStatePolicy.isValidTransition("pending", "running"));
        assertTrue(PreDefenseTaskStatePolicy.isValidTransition("running", "success"));
        assertTrue(PreDefenseTaskStatePolicy.isValidTransition("running", "failed"));
        assertTrue(PreDefenseTaskStatePolicy.isValidTransition("running", "canceled"));
        assertTrue(PreDefenseTaskStatePolicy.isValidTransition("running", "running"));

        assertFalse(PreDefenseTaskStatePolicy.isValidTransition("success", "running"));
        assertFalse(PreDefenseTaskStatePolicy.isValidTransition("failed", "pending"));
        assertFalse(PreDefenseTaskStatePolicy.isValidTransition("canceled", "running"));
        assertFalse(PreDefenseTaskStatePolicy.isValidTransition("unknown", "running"));
    }

    @Test
    void recognizesStartRetryAndTerminalStates() {
        assertTrue(PreDefenseTaskStatePolicy.isStartable("pending"));
        assertTrue(PreDefenseTaskStatePolicy.isRetryable("failed"));
        assertTrue(PreDefenseTaskStatePolicy.isTerminal("success"));
        assertTrue(PreDefenseTaskStatePolicy.isTerminal("failed"));
        assertTrue(PreDefenseTaskStatePolicy.isTerminal("canceled"));
        assertFalse(PreDefenseTaskStatePolicy.isTerminal("running"));
    }

    @Test
    void validatesProgressBoundaries() {
        assertTrue(PreDefenseTaskStatePolicy.isValidProgress(0));
        assertTrue(PreDefenseTaskStatePolicy.isValidProgress(100));
        assertFalse(PreDefenseTaskStatePolicy.isValidProgress(null));
        assertFalse(PreDefenseTaskStatePolicy.isValidProgress(-1));
        assertFalse(PreDefenseTaskStatePolicy.isValidProgress(101));
    }
}
