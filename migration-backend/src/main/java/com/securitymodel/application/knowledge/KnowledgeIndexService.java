package com.securitymodel.application.knowledge;

import com.securitymodel.application.resource.ResourceQueryService;
import com.securitymodel.application.task.TaskAccessGuard;
import com.securitymodel.application.task.TaskCommandException;
import com.securitymodel.domain.algorithm.Algorithm;
import com.securitymodel.domain.dataset.Dataset;
import com.securitymodel.domain.knowledge.KnowledgeDocument;
import com.securitymodel.domain.task.PreDefenseTask;
import com.securitymodel.infrastructure.persistence.mapper.AlgorithmMapper;
import com.securitymodel.infrastructure.persistence.mapper.KnowledgeDocumentMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

/**
 * Explicit, admin-triggered indexing. Nothing is indexed automatically at startup: the
 * operator decides when a corpus version becomes searchable.
 */
@Service
public class KnowledgeIndexService {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeIndexService.class);
    private static final String DEMO_ORIGIN = "demo";

    private final KnowledgeDocumentMapper documentMapper;
    private final KnowledgeDocumentWriter writer;
    private final AlgorithmMapper algorithmMapper;
    private final ResourceQueryService resourceQueryService;
    private final TaskAccessGuard taskAccessGuard;
    private final String sddRoot;
    private final int maxChunkChars;

    public KnowledgeIndexService(
            KnowledgeDocumentMapper documentMapper,
            KnowledgeDocumentWriter writer,
            AlgorithmMapper algorithmMapper,
            ResourceQueryService resourceQueryService,
            TaskAccessGuard taskAccessGuard,
            @Value("${security-model.knowledge.sdd-root:../docs/sdd}") String sddRoot,
            @Value("${security-model.knowledge.max-chunk-chars:800}") int maxChunkChars) {
        this.documentMapper = documentMapper;
        this.writer = writer;
        this.algorithmMapper = algorithmMapper;
        this.resourceQueryService = resourceQueryService;
        this.taskAccessGuard = taskAccessGuard;
        this.sddRoot = sddRoot;
        this.maxChunkChars = maxChunkChars;
    }

    public synchronized IndexStats reindexSdd() {
        Path root = Paths.get(sddRoot).toAbsolutePath().normalize();
        if (!Files.isDirectory(root)) {
            throw new IllegalArgumentException("SDD 文档根目录不可用");
        }
        int scanned = 0;
        int changed = 0;
        int skipped = 0;
        int failed = 0;
        int chunks = 0;
        List<String> failures = new ArrayList<>();
        try (Stream<Path> files = Files.list(root)) {
            List<Path> ordered = files
                    .filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
                    .filter(path -> path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".md"))
                    .sorted(Comparator.comparing(path -> path.getFileName().toString()))
                    .toList();
            for (Path file : ordered) {
                scanned++;
                try {
                    if (!file.toRealPath().startsWith(root.toRealPath())) {
                        failed++;
                        failures.add(file.getFileName().toString() + ": 路径越界");
                        continue;
                    }
                    String raw = Files.readString(file, StandardCharsets.UTF_8);
                    if (raw.startsWith("\uFEFF")) {
                        raw = raw.substring(1);
                    }
                    String sanitized = KnowledgeSanitizer.sanitize(raw);
                    String fileName = file.getFileName().toString();
                    String title = fileName.substring(0, fileName.length() - 3);
                    String sourceKey = "sdd:" + fileName;
                    UpsertResult result = upsert(KnowledgeDocument.SOURCE_SDD, sourceKey, title,
                            KnowledgeDocument.VISIBILITY_SHARED, null, sanitized);
                    if (result.changed()) {
                        changed++;
                        chunks += result.chunks();
                    } else {
                        skipped++;
                    }
                } catch (Exception exception) {
                    failed++;
                    failures.add(file.getFileName().toString() + ": " + exception.getClass().getSimpleName());
                    log.warn("知识库导入失败: file={}, reason={}", file.getFileName(), exception.getMessage());
                }
            }
        } catch (IOException exception) {
            throw new IllegalArgumentException("SDD 文档目录读取失败");
        }
        return new IndexStats(scanned, changed, skipped, failed, chunks, List.copyOf(failures));
    }

    public synchronized IndexStats reindexAlgorithms() {
        List<Algorithm> algorithms = algorithmMapper.selectList(null);
        int scanned = 0;
        int changed = 0;
        int skipped = 0;
        int failed = 0;
        int chunks = 0;
        List<String> failures = new ArrayList<>();
        for (Algorithm algorithm : algorithms) {
            if (algorithm == null || algorithm.getAlgorithmId() == null
                    || !Boolean.TRUE.equals(algorithm.getEnabled())) {
                continue;
            }
            scanned++;
            try {
                String markdown = algorithmMarkdown(algorithm);
                String sourceKey = "algorithm:" + algorithm.getAlgorithmId();
                UpsertResult result = upsert(KnowledgeDocument.SOURCE_ALGORITHM, sourceKey,
                        algorithm.getAlgorithmName(), KnowledgeDocument.VISIBILITY_SHARED, null, markdown);
                if (result.changed()) {
                    changed++;
                    chunks += result.chunks();
                } else {
                    skipped++;
                }
            } catch (Exception exception) {
                failed++;
                failures.add("algorithm:" + algorithm.getAlgorithmId() + ": "
                        + exception.getClass().getSimpleName());
                log.warn("算法知识导入失败: algorithmId={}, reason={}",
                        algorithm.getAlgorithmId(), exception.getMessage());
            }
        }
        return new IndexStats(scanned, changed, skipped, failed, chunks, List.copyOf(failures));
    }

    /** Private, owner-only report summary. Raw logs and artifact paths are never indexed. */
    public synchronized KnowledgeDocument indexTaskReport(Long taskId) {
        PreDefenseTask task = taskAccessGuard.requireOwned(taskId);
        if (DEMO_ORIGIN.equals(task.getDataOrigin())) {
            throw new TaskCommandException("KNOWLEDGE_DEMO_FORBIDDEN: 演示任务不可进入知识库");
        }
        Dataset dataset = task.getDatasetId() == null ? null : resourceQueryService.findDataset(task.getDatasetId());
        Algorithm algorithm = task.getAlgorithmId() == null ? null : resourceQueryService.findAlgorithm(task.getAlgorithmId());
        String summary = taskReportMarkdown(task, dataset, algorithm);
        String sourceKey = "task_report:" + taskId;
        IndexResult result = upsertDetailed(KnowledgeDocument.SOURCE_TASK_REPORT, sourceKey,
                "任务报告：" + safe(task.getTaskName(), 80),
                KnowledgeDocument.VISIBILITY_PRIVATE, task.getOwnerUserId(), summary);
        if (!result.changed()) {
            throw new TaskCommandException("KNOWLEDGE_REPORT_UNCHANGED: 报告摘要未变化，无需重建索引");
        }
        return result.document();
    }

    public synchronized boolean retire(Long documentId) {
        if (documentId == null) {
            return false;
        }
        return writer.retire(documentId, LocalDateTime.now());
    }

    private UpsertResult upsert(String sourceType, String sourceKey, String title,
                                String visibility, Long ownerUserId, String content) {
        IndexResult result = upsertDetailed(sourceType, sourceKey, title, visibility, ownerUserId, content);
        return new UpsertResult(result.changed(), result.chunks());
    }

    private IndexResult upsertDetailed(String sourceType, String sourceKey, String title,
                                       String visibility, Long ownerUserId, String content) {
        String hash = sha256(content);
        KnowledgeDocument active = documentMapper.findActiveBySourceKey(sourceKey);
        if (active != null && hash.equals(active.getContentHash())) {
            return new IndexResult(false, 0, active);
        }
        String safeTitle = safe(KnowledgeSanitizer.sanitize(title), 255);
        List<MarkdownKnowledgeChunker.Draft> drafts =
                MarkdownKnowledgeChunker.chunk(content, safeTitle, maxChunkChars);
        if (drafts.isEmpty()) {
            throw new IllegalArgumentException("文档没有可索引内容");
        }
        KnowledgeDocument saved = writer.saveVersion(sourceType, sourceKey, safeTitle, visibility,
                ownerUserId, hash, drafts, LocalDateTime.now());
        return new IndexResult(true, drafts.size(), saved);
    }

    private String algorithmMarkdown(Algorithm algorithm) {
        StringBuilder markdown = new StringBuilder();
        markdown.append("# ").append(safe(algorithm.getAlgorithmName(), 120)).append("\n\n");
        appendField(markdown, "算法类型", algorithm.getAlgorithmType());
        appendField(markdown, "防御阶段", algorithm.getDefenseStage());
        appendField(markdown, "算法分类", algorithm.getAlgorithmCategory());
        appendField(markdown, "实现标识", algorithm.getImplementationPath());
        appendField(markdown, "说明", algorithm.getDescription());
        appendField(markdown, "默认参数", algorithm.getDefaultConfig());
        appendField(markdown, "参数结构", algorithm.getConfigSchema());
        return KnowledgeSanitizer.sanitize(markdown.toString());
    }

    private String taskReportMarkdown(PreDefenseTask task, Dataset dataset, Algorithm algorithm) {
        StringBuilder markdown = new StringBuilder();
        markdown.append("# 任务报告：").append(safe(task.getTaskName(), 120)).append("\n\n");
        appendField(markdown, "任务状态", task.getStatus());
        appendField(markdown, "当前 run", task.getCurrentRunId());
        appendField(markdown, "当前 attempt", task.getCurrentAttempt() == null ? null
                : task.getCurrentAttempt().toString());
        appendField(markdown, "数据集", dataset == null ? null : dataset.getDatasetName());
        appendField(markdown, "算法", algorithm == null ? null : algorithm.getAlgorithmName());
        appendField(markdown, "任务说明", task.getDescription());
        appendField(markdown, "自定义参数", truncate(task.getCustomParams(), 2000));
        appendField(markdown, "结果摘要", truncate(task.getCleaningResult(), 4000));
        return KnowledgeSanitizer.sanitize(markdown.toString());
    }

    private void appendField(StringBuilder markdown, String label, String value) {
        if (value == null || value.isBlank()) {
            return;
        }
        markdown.append("## ").append(label).append("\n\n")
                .append(truncate(value, 4000)).append("\n\n");
    }

    private String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        String sanitized = KnowledgeSanitizer.sanitize(value);
        return sanitized.length() <= max ? sanitized : sanitized.substring(0, max);
    }

    private String safe(String value, int max) {
        String sanitized = KnowledgeSanitizer.sanitize(value == null ? "" : value);
        if (sanitized.isBlank()) {
            return "未命名";
        }
        return sanitized.length() <= max ? sanitized : sanitized.substring(0, max);
    }

    private String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte item : digest) {
                hex.append(String.format("%02x", item));
            }
            return hex.toString();
        } catch (Exception exception) {
            throw new IllegalStateException("无法计算知识文档摘要", exception);
        }
    }

    public record IndexStats(int scanned, int changed, int skipped, int failed, int chunks,
                             List<String> failures) {
    }

    private record UpsertResult(boolean changed, int chunks) {
    }

    private record IndexResult(boolean changed, int chunks, KnowledgeDocument document) {
    }
}
