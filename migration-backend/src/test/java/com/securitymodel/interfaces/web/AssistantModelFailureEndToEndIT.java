package com.securitymodel.interfaces.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.securitymodel.SecurityModelApplication;
import com.securitymodel.support.OwnershipE2ESupport;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Real Spring context + real assistant service + a local fake HTTP model endpoint.
 * The model always returns 500, so this proves the assistant degrades to a stable
 * model_unavailable response while an unrelated core API keeps working. It does not
 * claim to prove real DeepSeek prompt-injection behavior.
 */
@SpringBootTest(classes = SecurityModelApplication.class)
@AutoConfigureMockMvc
class AssistantModelFailureEndToEndIT {

    private static final String JWT_SECRET = "e2e-assistant-secret-0123456789abcdef0123456789";
    private static final String API_KEY = "local-fake-key-never-log";
    private static final AtomicReference<String> LAST_BODY = new AtomicReference<>("");
    private static final FakeModelServer MODEL = FakeModelServer.start();

    static {
        OwnershipE2ESupport.schemaUrl();
        createAssistantSchema();
    }

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private JdbcTemplate jdbc;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", OwnershipE2ESupport::schemaUrl);
        registry.add("spring.datasource.username", () -> OwnershipE2ESupport.USERNAME);
        registry.add("spring.datasource.password", () -> OwnershipE2ESupport.PASSWORD);
        registry.add("security-model.auth.jwt-secret", () -> JWT_SECRET);
        registry.add("security-model.ownership.admin-cross-user-read", () -> "false");
        registry.add("security-model.assistant.model.provider", () -> "deepseek");
        registry.add("security-model.assistant.model.enabled", () -> "true");
        registry.add("security-model.assistant.model.base-url", MODEL::url);
        registry.add("security-model.assistant.model.name", () -> "fake-chat");
        registry.add("security-model.assistant.model.api-key", () -> API_KEY);
        registry.add("security-model.assistant.model.timeout-seconds", () -> "3");
        registry.add("security-model.assistant.model.max-retries", () -> "0");
    }

    @AfterEach
    void removeAssistantRows() {
        jdbc.update("DELETE FROM sys_assistant_confirmation");
        jdbc.update("DELETE FROM sys_assistant_session");
        jdbc.update("DELETE FROM sys_assistant_tool_audit");
        jdbc.update("DELETE FROM sys_assistant_usage_daily");
        jdbc.update("DELETE FROM sys_request_idempotency WHERE operation LIKE 'assistant.%'");
    }

    @Test
    void modelServerErrorDegradesAssistantAndLeavesCoreTaskApiAvailable() throws Exception {
        String alice = login("alice");

        mockMvc.perform(post("/api/assistant/chat")
                        .header("Authorization", alice)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\":\"查看我的任务\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("model_unavailable"))
                .andExpect(jsonPath("$.toolCalls").value(0))
                .andExpect(jsonPath("$.modelCalls").value(1));

        mockMvc.perform(get("/api/pre-defense/100").header("Authorization", alice))
                .andExpect(status().isOk());

        String body = LAST_BODY.get();
        assertTrue(body.contains("\"question\""), "the fake endpoint must receive a real chat request");
        assertFalse(body.contains(API_KEY), "API key must stay in the Authorization header");
        assertFalse(body.contains("Authorization"), "credentials must not appear in the body");
        assertFalse(body.contains("C:\\"), "local Windows paths must not be sent to the model");
        assertTrue(MODEL.requestCount() >= 1);
    }

    private String login(String username) throws Exception {
        String body = objectMapper.writeValueAsString(Map.of(
                "username", username,
                "password", OwnershipE2ESupport.RAW_PASSWORD));
        String response = mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(response).path("token").asText();
    }

    private static final class FakeModelServer {
        private final HttpServer server;
        private final AtomicInteger requests = new AtomicInteger();

        private FakeModelServer(HttpServer server) {
            this.server = server;
        }

        static FakeModelServer start() {
            try {
                HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
                FakeModelServer result = new FakeModelServer(server);
                server.createContext("/chat/completions", exchange -> {
                    result.requests.incrementAndGet();
                    LAST_BODY.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                    byte[] bytes = "{\"error\":\"fake upstream unavailable\"}"
                            .getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().add("Content-Type", "application/json");
                    exchange.sendResponseHeaders(500, bytes.length);
                    exchange.getResponseBody().write(bytes);
                    exchange.close();
                });
                server.start();
                return result;
            } catch (IOException exception) {
                throw new IllegalStateException("cannot start fake assistant model", exception);
            }
        }

        String url() {
            return "http://127.0.0.1:" + server.getAddress().getPort() + "/chat/completions";
        }

        int requestCount() {
            return requests.get();
        }
    }

    private static void createAssistantSchema() {
        try (Connection connection = DriverManager.getConnection(
                OwnershipE2ESupport.schemaUrl(), OwnershipE2ESupport.USERNAME, OwnershipE2ESupport.PASSWORD);
             Statement statement = connection.createStatement()) {
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS sys_assistant_confirmation (
                      confirmation_id BIGINT NOT NULL AUTO_INCREMENT,
                      token_hash CHAR(64) NOT NULL,
                      user_id BIGINT NOT NULL,
                      operation VARCHAR(32) NOT NULL,
                      target_task_id BIGINT NULL,
                      target_run_id VARCHAR(64) NULL,
                      context_hash CHAR(64) NOT NULL,
                      request_json MEDIUMTEXT NOT NULL,
                      preview_json MEDIUMTEXT NOT NULL,
                      status VARCHAR(16) NOT NULL,
                      result_code INT NULL,
                      result_json MEDIUMTEXT NULL,
                      created_at DATETIME(3) NOT NULL,
                      expires_at DATETIME(3) NOT NULL,
                      consumed_at DATETIME(3) NULL,
                      PRIMARY KEY (confirmation_id),
                      UNIQUE KEY uk_assistant_confirmation_token (token_hash)
                    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
                    """);
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS sys_assistant_session (
                      session_id BIGINT NOT NULL AUTO_INCREMENT,
                      user_id BIGINT NOT NULL,
                      title VARCHAR(160) NOT NULL,
                      status VARCHAR(16) NOT NULL,
                      summary_json MEDIUMTEXT NULL,
                      created_at DATETIME(3) NOT NULL,
                      updated_at DATETIME(3) NOT NULL,
                      expires_at DATETIME(3) NOT NULL,
                      PRIMARY KEY (session_id)
                    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
                    """);
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS sys_assistant_tool_audit (
                      audit_id BIGINT NOT NULL AUTO_INCREMENT,
                      session_id BIGINT NULL,
                      user_id BIGINT NOT NULL,
                      tool_name VARCHAR(64) NOT NULL,
                      operation VARCHAR(32) NULL,
                      target_task_id BIGINT NULL,
                      target_run_id VARCHAR(64) NULL,
                      result_code VARCHAR(64) NOT NULL,
                      duration_ms INT NOT NULL,
                      created_at DATETIME(3) NOT NULL,
                      PRIMARY KEY (audit_id)
                    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
                    """);
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS sys_assistant_usage_daily (
                      user_id BIGINT NOT NULL,
                      usage_date DATE NOT NULL,
                      request_count INT NOT NULL,
                      updated_at DATETIME(3) NOT NULL,
                      PRIMARY KEY (user_id, usage_date)
                    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
                    """);
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS sys_task_dispatch_intent (
                      intent_id BIGINT NOT NULL AUTO_INCREMENT,
                      task_id BIGINT NOT NULL,
                      run_id VARCHAR(64) NOT NULL,
                      attempt INT NOT NULL,
                      status VARCHAR(20) NOT NULL,
                      schema_version INT NOT NULL DEFAULT 1,
                      request_json LONGTEXT NOT NULL,
                      request_hash CHAR(64) NOT NULL,
                      runtime_hash CHAR(64) NULL,
                      error_code VARCHAR(64) NULL,
                      created_at DATETIME(3) NOT NULL,
                      updated_at DATETIME(3) NOT NULL,
                      submitted_at DATETIME(3) NULL,
                      PRIMARY KEY (intent_id),
                      UNIQUE KEY uk_dispatch_intent_run (task_id, run_id, attempt)
                    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
                    """);
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS sys_request_idempotency (
                      record_id BIGINT NOT NULL AUTO_INCREMENT,
                      owner_user_id BIGINT NOT NULL,
                      operation VARCHAR(64) NOT NULL,
                      idempotency_key VARCHAR(128) NOT NULL,
                      request_hash CHAR(64) NOT NULL,
                      resource_id BIGINT NULL,
                      status VARCHAR(20) NOT NULL,
                      response_code INT NULL,
                      response_body LONGTEXT NULL,
                      created_at DATETIME(3) NOT NULL,
                      updated_at DATETIME(3) NOT NULL,
                      expires_at DATETIME(3) NOT NULL,
                      PRIMARY KEY (record_id),
                      UNIQUE KEY uk_request_idempotency (owner_user_id, operation, idempotency_key)
                    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
                    """);
        } catch (Exception exception) {
            throw new IllegalStateException("无法创建 R7 集成测试表", exception);
        }
    }
}
