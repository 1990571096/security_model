package com.securitymodel.application.knowledge;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.securitymodel.application.resource.ResourceQueryService;
import com.securitymodel.application.task.TaskAccessGuard;
import com.securitymodel.application.task.TaskCommandException;
import com.securitymodel.application.task.TaskFailureMessages;
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
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Explicit, admin-triggered indexing. Nothing is indexed automatically at startup: the
 * operator decides when a corpus version becomes searchable.
 */
@Service
public class KnowledgeIndexService {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeIndexService.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String DEMO_ORIGIN = "demo";
    private static final Pattern HISTORICAL_BLOCK = Pattern.compile(
            "(?is)<!--\\s*knowledge:historical\\s*-->.*?<!--\\s*/knowledge:historical\\s*-->");

    private final KnowledgeDocumentMapper documentMapper;
    private final KnowledgeDocumentWriter writer;
    private final AlgorithmMapper algorithmMapper;
    private final ResourceQueryService resourceQueryService;
    private final TaskAccessGuard taskAccessGuard;
    private final KnowledgeVectorStore vectorStore;
    private final String knowledgeRoot;
    private final int maxChunkChars;

    public KnowledgeIndexService(
            KnowledgeDocumentMapper documentMapper,
            KnowledgeDocumentWriter writer,
            AlgorithmMapper algorithmMapper,
            ResourceQueryService resourceQueryService,
            TaskAccessGuard taskAccessGuard,
            String knowledgeRoot,
            @Value("${security-model.knowledge.max-chunk-chars:800}") int maxChunkChars) {
        this(documentMapper, writer, algorithmMapper, resourceQueryService, taskAccessGuard,
                knowledgeRoot, maxChunkChars, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public KnowledgeIndexService(
            KnowledgeDocumentMapper documentMapper,
            KnowledgeDocumentWriter writer,
            AlgorithmMapper algorithmMapper,
            ResourceQueryService resourceQueryService,
            TaskAccessGuard taskAccessGuard,
            @Value("${security-model.knowledge.root:../knowledge-base}") String knowledgeRoot,
            @Value("${security-model.knowledge.max-chunk-chars:800}") int maxChunkChars,
            KnowledgeVectorStore vectorStore) {
        this.documentMapper = documentMapper;
        this.writer = writer;
        this.algorithmMapper = algorithmMapper;
        this.resourceQueryService = resourceQueryService;
        this.taskAccessGuard = taskAccessGuard;
        this.vectorStore = vectorStore;
        this.knowledgeRoot = knowledgeRoot;
        this.maxChunkChars = maxChunkChars;
    }

    public synchronized IndexStats reindexKnowledge() {
        Path root = Paths.get(knowledgeRoot).toAbsolutePath().normalize();
        if (!Files.isDirectory(root)) {
            throw new IllegalArgumentException("知识库目录不可用");
        }
        int scanned = 0;
        int changed = 0;
        int skipped = 0;
        int retired = 0;
        int failed = 0;
        int chunks = 0;
        List<String> failures = new ArrayList<>();
        Set<String> present = new HashSet<>();
        LocalDateTime now = LocalDateTime.now();
        try (Stream<Path> files = Files.list(root)) {
            List<Path> ordered = files
                    .filter(path -> path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".md"))
                    .sorted(Comparator.comparing(path -> path.getFileName().toString()))
                    .toList();
            for (Path file : ordered) {
                String fileName = file.getFileName().toString();
                String sourceKey = "knowledge:" + fileName;
                present.add(sourceKey);
                try {
                    if (!Files.readAttributes(file, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS)
                            .isRegularFile()) {
                        present.remove(sourceKey);
                        continue;
                    }
                    scanned++;
                    if (!file.toRealPath().startsWith(root.toRealPath())) {
                        failed++;
                        failures.add(file.getFileName().toString() + ": 路径越界");
                        continue;
                    }
                    String raw = Files.readString(file, StandardCharsets.UTF_8);
                    if (raw.startsWith("\uFEFF")) {
                        raw = raw.substring(1);
                    }
                    String currentFacts = stripHistoricalBlocks(raw);
                    if (currentFacts.isBlank()) {
                        // A file made only of historical markers is no longer a current-fact
                        // source. Retire its existing active version; otherwise old chunks
                        // would remain searchable after the rewrite.
                        if (retireSource(sourceKey, now)) {
                            retired++;
                        } else {
                            skipped++;
                        }
                        continue;
                    }
                    String sanitized = KnowledgeSanitizer.sanitize(currentFacts);
                    int leadingBreaks = leadingLineBreaks(currentFacts);
                    if (leadingBreaks > 0) {
                        // KnowledgeSanitizer trims leading whitespace; restore the original
                        // blank-line prefix so chunk line numbers still match the file.
                        sanitized = "\n".repeat(leadingBreaks) + sanitized;
                    }
                    String title = fileName.substring(0, fileName.length() - 3);
                    UpsertResult result = upsert(KnowledgeDocument.SOURCE_KNOWLEDGE, sourceKey, title,
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
            throw new IllegalArgumentException("知识库目录读取失败");
        }
        for (KnowledgeDocument document : documentMapper.findActiveFileDocuments()) {
            if (KnowledgeDocument.SOURCE_SDD.equals(document.getSourceType())
                    || !present.contains(document.getSourceKey())) {
                if (retire(document.getDocumentId())) {
                    retired++;
                }
            }
        }
        return new IndexStats(scanned, changed, skipped, retired, failed, chunks, List.copyOf(failures));
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
        return new IndexStats(scanned, changed, skipped, 0, failed, chunks, List.copyOf(failures));
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
        LocalDateTime now = LocalDateTime.now();
        boolean retired = writer.retire(documentId, now);
        if (retired) {
            // MySQL is the source of truth; the vector cleanup is best effort. A stale
            // vector can only occupy a candidate slot and is still filtered by the SQL
            // ACL query, never used as proof that the document is active.
            if (removeVectorsQuietly(documentId) && documentMapper != null) {
                documentMapper.markVectorCleanupClean(documentId, now);
            }
        }
        return retired;
    }

    private boolean retireSource(String sourceKey, LocalDateTime now) {
        KnowledgeDocument active = documentMapper.findActiveBySourceKey(sourceKey);
        if (active == null) {
            return false;
        }
        if (!writer.retire(active.getDocumentId(), now)) {
            return false;
        }
        if (removeVectorsQuietly(active.getDocumentId())) {
            documentMapper.markVectorCleanupClean(active.getDocumentId(), now);
        }
        log.info("空白或仅含历史内容的文档已退役: sourceKey={}, documentId={}", sourceKey,
                active.getDocumentId());
        return true;
    }

    /**
     * Bounded reconciliation for a failed Redis cleanup after retirement. The cleanup
     * state is persisted, so a permanent failure moves out of the first-page window
     * instead of starving later retired documents. MySQL remains the authority: every
     * candidate is already retired, so a repeated VREM is harmless.
     */
    public synchronized int reconcileRetiredVectors(int limit) {
        if (vectorStore == null || !vectorStore.enabled()) {
            return 0;
        }
        int bounded = Math.max(1, Math.min(200, limit));
        LocalDateTime now = LocalDateTime.now();
        List<KnowledgeDocument> retired = documentMapper.findRetiredForVectorCleanup(now, bounded);
        int cleaned = 0;
        for (KnowledgeDocument document : retired) {
            try {
                if (vectorStore.removeDocument(document.getDocumentId())) {
                    documentMapper.markVectorCleanupClean(document.getDocumentId(), now);
                    cleaned++;
                } else {
                    markVectorCleanupFailed(document, now);
                }
            } catch (RuntimeException exception) {
                markVectorCleanupFailed(document, now);
                log.warn("退役知识向量对账失败: documentId={}, reason={}",
                        document.getDocumentId(), exception.getClass().getSimpleName());
            }
        }
        return cleaned;
    }

    private void markVectorCleanupFailed(KnowledgeDocument document, LocalDateTime now) {
        int attempts = document.getVectorCleanupAttempts() == null
                ? 0 : Math.max(0, document.getVectorCleanupAttempts());
        long delaySeconds = 60L << Math.min(6, attempts);
        LocalDateTime nextAt = now.plusSeconds(Math.min(3600, delaySeconds));
        documentMapper.markVectorCleanupFailed(document.getDocumentId(), now, nextAt);
        log.warn("退役知识向量对账延后: documentId={}, attempts={}, nextAt={}",
                document.getDocumentId(), attempts + 1, nextAt);
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
            indexVectorsQuietly(active.getDocumentId(), active.getVisibility(), active.getOwnerUserId());
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
        if (active != null) {
            removeVectorsQuietly(active.getDocumentId());
        }
        indexVectorsQuietly(saved.getDocumentId(), saved.getVisibility(), saved.getOwnerUserId());
        return new IndexResult(true, drafts.size(), saved);
    }

    private boolean removeVectorsQuietly(Long documentId) {
        if (vectorStore == null || !vectorStore.enabled()) {
            return false;
        }
        try {
            return vectorStore.removeDocument(documentId);
        } catch (RuntimeException exception) {
            log.warn("旧知识向量清理失败: documentId={}, reason={}", documentId,
                    exception.getClass().getSimpleName());
            return false;
        }
    }

    private void indexVectorsQuietly(Long documentId, String visibility, Long ownerUserId) {
        if (vectorStore == null || !vectorStore.enabled()) {
            return;
        }
        try {
            vectorStore.indexDocument(documentId, visibility, ownerUserId);
        } catch (RuntimeException exception) {
            // Vector indexing is an enhancement; lexical retrieval remains the fallback.
            log.warn("知识向量索引失败，保留关键词检索: documentId={}, reason={}",
                    documentId, exception.getClass().getSimpleName());
        }
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
        appendField(markdown, "进度", task.getProgress() == null ? null : task.getProgress().toString());
        appendField(markdown, "数据集", dataset == null ? null : dataset.getDatasetName());
        appendField(markdown, "算法", algorithm == null ? null : algorithm.getAlgorithmName());
        appendField(markdown, "任务说明", task.getDescription());
        appendResultSummary(markdown, task.getCleaningResult());
        return KnowledgeSanitizer.sanitize(markdown.toString());
    }

    /**
     * Report indexing uses a parsed field whitelist, never the raw result JSON. The raw
     * envelope contains artifact/log/data paths and runtime executables that must not be
     * embedded in a searchable document.
     */
    private void appendResultSummary(StringBuilder markdown, String rawResult) {
        if (rawResult == null || rawResult.isBlank()) {
            return;
        }
        try {
            JsonNode root = JSON.readTree(rawResult);
            String errorCode = root.path("error_code").asText(null);
            appendField(markdown, "错误码", errorCode);
            if (errorCode != null && !errorCode.isBlank()) {
                appendField(markdown, "失败摘要", TaskFailureMessages.forCode(errorCode));
            }
            JsonNode metrics = root.path("metrics");
            appendField(markdown, "清洗纯度",
                    metrics.path("clean_purity_percent_by_isolation_ratio").toString());
            appendField(markdown, "保留样本数",
                    metrics.path("selected_sample_count_by_isolation_ratio").toString());
            appendField(markdown, "选择类型", root.path("artifacts").path("selection_kind").asText(null));
            JsonNode config = root.path("effective_config");
            if (config.isObject()) {
                StringBuilder safeConfig = new StringBuilder();
                for (String key : List.of("dataset", "trigger_type", "trigger_label", "poisoned_mode",
                        "poisoned_portion", "batch_size", "num_epochs", "seed")) {
                    if (config.has(key) && !config.get(key).isNull()) {
                        safeConfig.append(key).append("=").append(config.get(key).asText()).append(" ");
                    }
                }
                appendField(markdown, "生效配置摘要", safeConfig.toString());
            }
        } catch (Exception exception) {
            appendField(markdown, "结果摘要", "unavailable");
        }
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

    /**
     * Removes marked historical notes while retaining source line numbers.
     */
    static String stripHistoricalBlocks(String markdown) {
        if (markdown == null || markdown.isBlank()) {
            return "";
        }
        java.util.regex.Matcher matcher = HISTORICAL_BLOCK.matcher(markdown);
        StringBuilder result = new StringBuilder(markdown.length());
        int lastEnd = 0;
        while (matcher.find()) {
            result.append(markdown, lastEnd, matcher.start());
            // Keep the original line count so citations after a historical block still point
            // at the real Markdown line. A single-line inline block is replaced by a space.
            int lineBreaks = (int) matcher.group().chars().filter(ch -> ch == '\n').count();
            result.append(lineBreaks == 0 ? " " : "\n".repeat(lineBreaks));
            lastEnd = matcher.end();
        }
        result.append(markdown, lastEnd, markdown.length());
        return result.toString();
    }

    private static int leadingLineBreaks(String text) {
        int count = 0;
        for (int index = 0; index < text.length(); index++) {
            char value = text.charAt(index);
            if (value == '\n') {
                count++;
            } else if (value == '\r') {
                count++;
                if (index + 1 < text.length() && text.charAt(index + 1) == '\n') {
                    index++;
                }
            } else if (!Character.isWhitespace(value)) {
                break;
            }
        }
        return count;
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

    public record IndexStats(int scanned, int changed, int skipped, int retired, int failed, int chunks,
                             List<String> failures) {
    }

    private record UpsertResult(boolean changed, int chunks) {
    }

    private record IndexResult(boolean changed, int chunks, KnowledgeDocument document) {
    }
}
