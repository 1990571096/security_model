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

import java.sql.DriverManager;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.List;
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
                  PRIMARY KEY (document_id),
                  UNIQUE KEY uk_knowledge_document_source_version (source_key, version),
                  KEY idx_knowledge_document_active (source_key, status),
                  KEY idx_knowledge_document_acl (visibility, owner_user_id, status)
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
