package com.securitymodel.infrastructure.observability;

import com.sun.management.OperatingSystemMXBean;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryUsage;
import java.lang.management.RuntimeMXBean;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Read-only host/JVM metrics for the runtime monitor.
 *
 * Values are sampled from the local operating system at request time. Missing
 * capabilities return null/-1 markers instead of fabricated zeroes.
 */
@Service
public class HostRuntimeMetricsService {

    public Map<String, Object> snapshot() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("available", true);
        result.put("sampledAt", LocalDateTime.now().toString());

        RuntimeMXBean runtime = ManagementFactory.getRuntimeMXBean();
        MemoryUsage heap = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage();
        OperatingSystemMXBean system = ManagementFactory.getOperatingSystemMXBean() instanceof OperatingSystemMXBean
                ? (OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean()
                : null;

        result.put("osName", ManagementFactory.getOperatingSystemMXBean().getName());
        result.put("osVersion", ManagementFactory.getOperatingSystemMXBean().getVersion());
        result.put("osArch", ManagementFactory.getOperatingSystemMXBean().getArch());
        result.put("availableProcessors", ManagementFactory.getOperatingSystemMXBean().getAvailableProcessors());
        result.put("javaVersion", Runtime.version().toString());
        result.put("jvmUptimeSeconds", runtime.getUptime() / 1000);

        if (system != null) {
            long total = system.getTotalMemorySize();
            long free = system.getFreeMemorySize();
            result.put("memoryTotalBytes", total);
            result.put("memoryFreeBytes", free);
            result.put("memoryUsedBytes", Math.max(0, total - free));
            result.put("memoryUsedPercent", percent(total - free, total));
            result.put("cpuLoadPercent", oneDecimal(system.getCpuLoad() * 100));
            result.put("processCpuLoadPercent", oneDecimal(system.getProcessCpuLoad() * 100));
        } else {
            result.put("memoryTotalBytes", null);
            result.put("memoryFreeBytes", null);
            result.put("memoryUsedBytes", null);
            result.put("memoryUsedPercent", null);
            result.put("cpuLoadPercent", null);
            result.put("processCpuLoadPercent", null);
        }

        result.put("heapUsedBytes", heap.getUsed());
        result.put("heapMaxBytes", heap.getMax() > 0 ? heap.getMax() : null);
        result.put("heapUsedPercent", heap.getMax() > 0 ? percent(heap.getUsed(), heap.getMax()) : null);

        Map<String, Object> disk = diskMetrics();
        result.put("disk", disk);
        return result;
    }

    private Map<String, Object> diskMetrics() {
        Map<String, Object> disk = new LinkedHashMap<>();
        Path path = Path.of(System.getProperty("user.dir", ".")).toAbsolutePath().normalize();
        try {
            FileStore store = Files.getFileStore(path);
            long total = store.getTotalSpace();
            long usable = store.getUsableSpace();
            disk.put("available", true);
            disk.put("path", path.toString());
            disk.put("store", store.name());
            disk.put("type", store.type());
            disk.put("totalBytes", total);
            disk.put("usableBytes", usable);
            disk.put("usedBytes", Math.max(0, total - usable));
            disk.put("usedPercent", percent(total - usable, total));
        } catch (IOException | SecurityException exception) {
            disk.put("available", false);
            disk.put("path", path.toString());
            disk.put("message", "本地磁盘空间暂不可读");
        }
        return disk;
    }

    private static double percent(long used, long total) {
        if (total <= 0) return 0;
        return oneDecimal(used * 100.0 / total);
    }

    private static double oneDecimal(double value) {
        if (!Double.isFinite(value) || value < 0) return -1;
        return Math.round(value * 10.0) / 10.0;
    }
}
