package com.securitymodel.infrastructure.observability;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HostRuntimeMetricsServiceTest {

    @Test
    void snapshotExposesRealHostMemoryJvmAndDiskFields() {
        Map<String, Object> snapshot = new HostRuntimeMetricsService().snapshot();

        assertEquals(true, snapshot.get("available"));
        assertNotNull(snapshot.get("osName"));
        assertNotNull(snapshot.get("osArch"));
        assertTrue(((Number) snapshot.get("availableProcessors")).intValue() >= 1);
        assertTrue(((Number) snapshot.get("jvmUptimeSeconds")).longValue() >= 0);
        assertTrue(((Number) snapshot.get("memoryTotalBytes")).longValue() > 0);
        assertTrue(((Number) snapshot.get("memoryUsedPercent")).doubleValue() >= 0);
        assertTrue(((Number) snapshot.get("heapUsedBytes")).longValue() >= 0);
        assertTrue(((Number) snapshot.get("heapUsedPercent")).doubleValue() >= 0);

        Map<?, ?> disk = (Map<?, ?>) snapshot.get("disk");
        assertNotNull(disk);
        assertEquals(true, disk.get("available"));
        assertNotNull(disk.get("path"));
        assertTrue(((Number) disk.get("totalBytes")).longValue() > 0);
        assertTrue(((Number) disk.get("usedPercent")).doubleValue() >= 0);
    }

    @Test
    void sampledAtIsParseableLocalDateTime() {
        Object sampledAt = new HostRuntimeMetricsService().snapshot().get("sampledAt");
        assertNotNull(sampledAt);
        try {
            LocalDateTime.parse(String.valueOf(sampledAt));
        } catch (DateTimeParseException exception) {
            throw new AssertionError("采样时间必须是可解析的 LocalDateTime", exception);
        }
    }
}
