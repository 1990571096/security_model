package com.securitymodel.application.knowledge;

import com.baomidou.mybatisplus.autoconfigure.MybatisPlusAutoConfiguration;
import com.securitymodel.domain.knowledge.KnowledgeChunk;
import com.securitymodel.domain.knowledge.KnowledgeDocument;
import com.securitymodel.infrastructure.persistence.mapper.KnowledgeChunkMapper;
import com.securitymodel.infrastructure.persistence.mapper.KnowledgeDocumentMapper;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.JdbcTemplateAutoConfiguration;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.junit.jupiter.api.Assumptions;

import java.sql.DriverManager;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Real MySQL proof for R6 ACL: retired versions never surface, shared documents are
 * visible to everyone, and a private report is visible only to its owner.
 */
@SpringJUnitConfig(KnowledgeRetrievalMySqlIT.KnowledgeTestConfiguration.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class KnowledgeRetrievalMySqlIT {

    private static final String SCHEMA = "security_model_knowledge_it_"
            + UUID.randomUUID().toString().replace("-", "");
    private static final String HOST = env("MYSQL_IT_HOST", "localhost");
    private static final int PORT = Integer.parseInt(env("MYSQL_IT_PORT", "3306"));
    private static final String USERNAME = env("MYSQL_IT_USERNAME", "root");
    private static final String PASSWORD = env("MYSQL_IT_PASSWORD", "");

    static {
        createSchema();
    }

    @Autowired
    private KnowledgeDocumentMapper documentMapper;

    @Autowired
    private KnowledgeChunkMapper chunkMapper;

    @Autowired
    private KnowledgeDocumentWriter writer;

    @Autowired
    private JdbcTemplate jdbc;

    @DynamicPropertySource
    static void mysqlProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", KnowledgeRetrievalMySqlIT::schemaUrl);
        registry.add("spring.datasource.username", () -> USERNAME);
        registry.add("spring.datasource.password", () -> PASSWORD);
        registry.add("spring.datasource.driver-class-name", () -> "com.mysql.cj.jdbc.Driver");
    }

    @BeforeEach
    void resetTables() {
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS sys_knowledge_document (
                  document_id BIGINT NOT NULL AUTO_INCREMENT,
                  source_type VARCHAR(32) NOT NULL,
                  source_key VARCHAR(256) NOT NULL,
                  version INT NOT NULL,
                  title VARCHAR(255) NOT NULL,
                  visibility VARCHAR(16) NOT NULL,
                  owner_user_id BIGINT NULL,
                  content_hash CHAR(64) NOT NULL,
                  status VARCHAR(16) NOT NULL,
                  created_at DATETIME(3) NOT NULL,
                  updated_at DATETIME(3) NOT NULL,
                  vector_cleanup_status VARCHAR(16) NOT NULL DEFAULT 'pending',
                  vector_cleanup_attempts INT NOT NULL DEFAULT 0,
                  vector_cleanup_next_at DATETIME(3) NULL,
                  vector_cleanup_updated_at DATETIME(3) NULL,
                  PRIMARY KEY (document_id),
                  UNIQUE KEY uk_knowledge_document_source_version (source_key, version),
                  KEY idx_knowledge_document_active (source_key, status),
                  KEY idx_knowledge_document_acl (visibility, owner_user_id, status),
                  KEY idx_knowledge_document_vector_cleanup
                      (status, vector_cleanup_status, vector_cleanup_next_at, document_id)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
                """);
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS sys_knowledge_chunk (
                  chunk_id BIGINT NOT NULL AUTO_INCREMENT,
                  document_id BIGINT NOT NULL,
                  chunk_index INT NOT NULL,
                  section_path VARCHAR(512) NULL,
                  content MEDIUMTEXT NOT NULL,
                  start_line INT NOT NULL,
                  end_line INT NOT NULL,
                  created_at DATETIME(3) NOT NULL,
                  PRIMARY KEY (chunk_id),
                  UNIQUE KEY uk_knowledge_chunk_document_index (document_id, chunk_index),
                  KEY idx_knowledge_chunk_document (document_id)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
                """);
        jdbc.update("DELETE FROM sys_knowledge_chunk");
        jdbc.update("DELETE FROM sys_knowledge_document");
    }

    @Test
    void sharedIsVisibleToAllAndPrivateOnlyToOwner() {
        Long shared = insertDocument("sdd:05.md", 1, "shared", null, "shared");
        insertChunk(shared, 0, "共享租约知识");
        Long ownerSeven = insertDocument("task_report:7", 1, "private", 7L, "owner-seven");
        insertChunk(ownerSeven, 0, "用户七的私有报告");
        Long ownerEight = insertDocument("task_report:8", 1, "private", 8L, "owner-eight");
        insertChunk(ownerEight, 0, "用户八的私有报告");

        List<KnowledgeSearchRow> asSeven = documentMapper.findSearchableForUser(7L, 100);
        List<KnowledgeSearchRow> asEight = documentMapper.findSearchableForUser(8L, 100);
        List<KnowledgeSearchRow> all = documentMapper.findSearchableAll(100);

        assertEquals(2, asSeven.size());
        assertTrue(asSeven.stream().anyMatch(row -> row.getSourceKey().equals("sdd:05.md")));
        assertTrue(asSeven.stream().anyMatch(row -> row.getSourceKey().equals("task_report:7")));
        assertTrue(asSeven.stream().noneMatch(row -> row.getSourceKey().equals("task_report:8")));
        assertEquals(2, asEight.size());
        assertEquals(3, all.size());
    }

    @Test
    void retiredVersionIsNeverRecallable() {
        Long old = insertDocument("sdd:05.md", 1, "shared", null, "old");
        insertChunk(old, 0, "旧版本内容");
        Long current = insertDocument("sdd:05.md", 2, "shared", null, "current");
        insertChunk(current, 0, "新版本内容");

        assertEquals(1, documentMapper.retireOtherVersions("sdd:05.md", current, LocalDateTime.now()));
        assertEquals(1L, documentMapper.countActive());
        List<KnowledgeSearchRow> rows = documentMapper.findSearchableForUser(9L, 100);
        assertEquals(1, rows.size());
        assertEquals("新版本内容", rows.get(0).getContent());
        assertEquals(2, rows.get(0).getVersion());
    }

    @Test
    void explicitRetireRemovesTheDocumentFromSearch() {
        Long document = insertDocument("task_report:1", 1, "private", 1L, "report");
        insertChunk(document, 0, "私有摘要");
        assertEquals(1, documentMapper.retireById(document, LocalDateTime.now()));
        assertTrue(documentMapper.findSearchableForUser(1L, 100).isEmpty());
        assertEquals(0L, documentMapper.countActive());
    }

    @Test
    void writerCreatesOneActiveVersionAndRetiresTheOldOne() {
        KnowledgeDocument first = writer.saveVersion("sdd", "sdd:writer.md", "首版", "shared",
                null, "hash-1",
                List.of(new MarkdownKnowledgeChunker.Draft("首版", "章节", "第一版内容", 1, 1)),
                LocalDateTime.now());
        KnowledgeDocument second = writer.saveVersion("sdd", "sdd:writer.md", "新版", "shared",
                null, "hash-2",
                List.of(new MarkdownKnowledgeChunker.Draft("新版", "章节", "第二版内容", 1, 1)),
                LocalDateTime.now());

        assertEquals(2, second.getVersion());
        assertEquals(1L, documentMapper.countActive());
        List<KnowledgeSearchRow> rows = documentMapper.findSearchableForUser(5L, 100);
        assertEquals(1, rows.size());
        assertEquals("第二版内容", rows.get(0).getContent());
        assertEquals(2, rows.get(0).getVersion());
        assertEquals(1L, jdbc.queryForObject(
                "SELECT COUNT(*) FROM sys_knowledge_document WHERE document_id = ? AND status='retired'",
                Long.class, first.getDocumentId()));
    }

    @Test
    void retireSurvivesRedisFailureThenReconcilesResidualVectors() throws Exception {
        Assumptions.assumeTrue("true".equals(System.getenv("REDIS_IT")),
                "real Redis is required for retirement compensation");
        LettuceConnectionFactory factory = new LettuceConnectionFactory("127.0.0.1", 6379);
        factory.afterPropertiesSet();
        StringRedisTemplate redis = new StringRedisTemplate(factory);
        redis.afterPropertiesSet();
        String key = "security-model:it:vectors:" + UUID.randomUUID();
        try {
            KnowledgeVectorStore realStore = new KnowledgeVectorStore(
                    redis, chunkMapper, new LocalEmbeddingModel(true), true, key, 40);
            Long documentId = insertDocument("task_report:retire-compensation", 1,
                    "private", 1L, "retirement");
            insertChunk(documentId, 0, "退役文档的残留向量内容");
            assertEquals(1, realStore.indexDocument(documentId, "private", 1L));

            FlakyVectorStore flaky = new FlakyVectorStore(realStore, redis, chunkMapper, key);
            KnowledgeIndexService index = new KnowledgeIndexService(
                    documentMapper, writer, null, null, null, "unused", 800, flaky);
            flaky.failNextRemove = true;
            assertTrue(index.retire(documentId));
            assertEquals("retired", jdbc.queryForObject(
                    "SELECT status FROM sys_knowledge_document WHERE document_id = ?",
                    String.class, documentId));
            assertTrue(realStore.search("退役文档的残留向量内容", 5, 1L, false).hits().stream()
                    .anyMatch(hit -> hit.chunkId() == chunkIdOf(documentId)),
                    "the simulated Redis outage must leave a residual vector");

            assertEquals(1, index.reconcileRetiredVectors(10));
            assertTrue(realStore.search("退役文档的残留向量内容", 5, 1L, false).hits().stream()
                    .noneMatch(hit -> hit.chunkId() == chunkIdOf(documentId)));
            assertEquals(0, jdbc.queryForObject(
                    "SELECT COUNT(*) FROM sys_knowledge_document WHERE document_id = ? AND status = 'active'",
                    Integer.class, documentId));
            // Repeated reconciliation is safe when the vectors are already gone.
            index.reconcileRetiredVectors(10);
            assertTrue(realStore.search("退役文档的残留向量内容", 5, 1L, false).hits().stream()
                    .noneMatch(hit -> hit.chunkId() == chunkIdOf(documentId)));
        } finally {
            redis.delete(key);
            factory.destroy();
        }
    }

    @Test
    void retiredVectorCleanupAdvancesPastFailuresAndSurvivesRestart() throws Exception {
        Assumptions.assumeTrue("true".equals(System.getenv("REDIS_IT")),
                "real Redis is required for retirement compensation");
        LettuceConnectionFactory factory = new LettuceConnectionFactory("127.0.0.1", 6379);
        factory.afterPropertiesSet();
        StringRedisTemplate template = new StringRedisTemplate(factory);
        template.afterPropertiesSet();
        String key = "security-model:it:vectors:" + UUID.randomUUID();
        try {
            KnowledgeVectorStore realStore = new KnowledgeVectorStore(
                    template, chunkMapper, new LocalEmbeddingModel(true), true, key, 40);
            List<Long> ids = new ArrayList<>();
            for (int i = 0; i < 5; i++) {
                Long id = insertRetiredDocument("task_report:starvation-" + i, "退役饥饿测试-" + i);
                insertChunk(id, 0, "退役饥饿测试-" + i);
                assertEquals(1, realStore.indexDocument(id, "private", 1L));
                ids.add(id);
            }

            FlakyVectorStore flaky = new FlakyVectorStore(
                    realStore, template, chunkMapper, key);
            flaky.failDocumentIds.add(ids.get(0));
            KnowledgeIndexService index = new KnowledgeIndexService(
                    documentMapper, writer, null, null, null, "unused", 800, flaky);

            // The first page fails one document; it must not block the other document in
            // the page, and the failed row must leave the next scan window.
            assertEquals(1, index.reconcileRetiredVectors(2));
            assertEquals("failed", cleanupStatus(ids.get(0)));
            assertEquals("clean", cleanupStatus(ids.get(1)));
            assertEquals(2, index.reconcileRetiredVectors(2));
            assertEquals("clean", cleanupStatus(ids.get(2)));
            assertEquals("clean", cleanupStatus(ids.get(3)));
            assertEquals(1, index.reconcileRetiredVectors(2));
            assertEquals("clean", cleanupStatus(ids.get(4)));
            assertEquals(0, index.reconcileRetiredVectors(2));

            // A fresh service instance reads the persisted retry time; no in-memory
            // cursor is required to reach the failed record after a restart.
            jdbc.update("UPDATE sys_knowledge_document SET vector_cleanup_next_at = "
                    + "DATE_SUB(NOW(3), INTERVAL 1 SECOND) WHERE document_id = ?", ids.get(0));
            KnowledgeIndexService afterRestart = new KnowledgeIndexService(
                    documentMapper, writer, null, null, null, "unused", 800, flaky);
            assertEquals(1, afterRestart.reconcileRetiredVectors(2));
            assertEquals("clean", cleanupStatus(ids.get(0)));
            assertTrue(realStore.search("退役饥饿测试-0", 5, 1L, false).hits().stream()
                    .noneMatch(hit -> hit.chunkId() == chunkIdOf(ids.get(0))));
        } finally {
            template.delete(key);
            factory.destroy();
        }
    }

    private String cleanupStatus(Long documentId) {
        return jdbc.queryForObject(
                "SELECT vector_cleanup_status FROM sys_knowledge_document WHERE document_id = ?",
                String.class, documentId);
    }

    private Long insertRetiredDocument(String sourceKey, String title) {
        Long id = insertDocument(sourceKey, 1, "private", 1L, title);
        jdbc.update("UPDATE sys_knowledge_document SET status = 'retired', "
                + "vector_cleanup_status = 'pending', vector_cleanup_attempts = 0, "
                + "vector_cleanup_next_at = NOW(3), vector_cleanup_updated_at = NOW(3) "
                + "WHERE document_id = ?", id);
        return id;
    }

    private long chunkIdOf(Long documentId) {
        return jdbc.queryForObject(
                "SELECT chunk_id FROM sys_knowledge_chunk WHERE document_id = ? ORDER BY chunk_id LIMIT 1",
                Long.class, documentId);
    }

    private Long insertDocument(String sourceKey, int version, String visibility,
                                Long ownerUserId, String title) {
        KnowledgeDocument document = new KnowledgeDocument();
        document.setSourceType(sourceKey.startsWith("sdd:") ? "sdd" : "task_report");
        document.setSourceKey(sourceKey);
        document.setVersion(version);
        document.setTitle(title);
        document.setVisibility(visibility);
        document.setOwnerUserId(ownerUserId);
        document.setContentHash("hash-" + version);
        document.setStatus("active");
        document.setCreatedAt(LocalDateTime.now());
        document.setUpdatedAt(LocalDateTime.now());
        documentMapper.insert(document);
        return document.getDocumentId();
    }

    private void insertChunk(Long documentId, int index, String content) {
        KnowledgeChunk chunk = new KnowledgeChunk();
        chunk.setDocumentId(documentId);
        chunk.setChunkIndex(index);
        chunk.setSectionPath("section");
        chunk.setContent(content);
        chunk.setStartLine(1);
        chunk.setEndLine(2);
        chunk.setCreatedAt(LocalDateTime.now());
        chunkMapper.insert(chunk);
    }

    @AfterAll
    static void dropSchema() throws Exception {
        if (!SCHEMA.matches("security_model_knowledge_it_[a-f0-9]{32}")) {
            return;
        }
        try (var connection = DriverManager.getConnection(adminUrl(), USERNAME, PASSWORD);
             Statement statement = connection.createStatement()) {
            statement.execute("DROP DATABASE `" + SCHEMA + "`");
        }
    }

    private static void createSchema() {
        if (!SCHEMA.matches("security_model_knowledge_it_[a-f0-9]{32}")) {
            throw new IllegalStateException("Refusing to create a non-isolated MySQL test schema");
        }
        try (var connection = DriverManager.getConnection(adminUrl(), USERNAME, PASSWORD);
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE DATABASE `" + SCHEMA
                    + "` CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci");
        } catch (Exception exception) {
            throw new ExceptionInInitializerError(exception);
        }
    }

    private static String schemaUrl() {
        return "jdbc:mysql://" + HOST + ":" + PORT + "/" + SCHEMA
                + "?useUnicode=true&characterEncoding=utf8&useSSL=false&allowPublicKeyRetrieval=true"
                + "&serverTimezone=UTC";
    }

    private static String adminUrl() {
        return "jdbc:mysql://" + HOST + ":" + PORT + "/?useSSL=false&allowPublicKeyRetrieval=true"
                + "&serverTimezone=UTC";
    }

    private static String env(String name, String defaultValue) {
        String value = System.getenv(name);
        return value == null ? defaultValue : value;
    }

    private static class FlakyVectorStore extends KnowledgeVectorStore {
        private final KnowledgeVectorStore delegate;
        private final Set<Long> failDocumentIds = new HashSet<>();
        private volatile boolean failNextRemove;

        FlakyVectorStore(KnowledgeVectorStore delegate, StringRedisTemplate redis,
                         KnowledgeChunkMapper mapper, String key) {
            super(redis, mapper, new LocalEmbeddingModel(true), true, key, 40);
            this.delegate = delegate;
        }

        @Override
        public boolean enabled() {
            return delegate.enabled();
        }

        @Override
        public int indexDocument(Long documentId, String visibility, Long ownerUserId) {
            return delegate.indexDocument(documentId, visibility, ownerUserId);
        }

        @Override
        public boolean removeDocument(Long documentId) {
            if (failNextRemove) {
                failNextRemove = false;
                throw new IllegalStateException("simulated-redis-outage");
            }
            if (failDocumentIds.remove(documentId)) {
                throw new IllegalStateException("simulated-document-outage");
            }
            return delegate.removeDocument(documentId);
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    @MapperScan("com.securitymodel.infrastructure.persistence.mapper")
    @ImportAutoConfiguration({
            DataSourceAutoConfiguration.class,
            JdbcTemplateAutoConfiguration.class,
            MybatisPlusAutoConfiguration.class
    })
    static class KnowledgeTestConfiguration {
        @org.springframework.context.annotation.Bean
        KnowledgeDocumentWriter knowledgeDocumentWriter(KnowledgeDocumentMapper documentMapper,
                                                         KnowledgeChunkMapper chunkMapper) {
            return new KnowledgeDocumentWriter(documentMapper, chunkMapper);
        }
    }
}
