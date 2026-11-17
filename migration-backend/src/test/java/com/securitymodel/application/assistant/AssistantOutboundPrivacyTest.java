package com.securitymodel.application.assistant;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.securitymodel.application.audit.AuthenticatedUser;
import com.securitymodel.application.audit.CurrentUserService;
import com.securitymodel.domain.assistant.AssistantSession;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Exercises the real ChatService and real DeepSeek adapter against a local HTTP server.
 * This is the final outbound request body, not just a sanitizer unit assertion.
 */
class AssistantOutboundPrivacyTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void finalModelRequestBodyContainsNoFilesystemPathsOrKey() throws Exception {
        try (FakeServer server = new FakeServer()) {
            DeepSeekAssistantModel model = new DeepSeekAssistantModel(objectMapper, true,
                    server.url(), "deepseek-chat", "sk-live-secret", 5, 128, 0);
            AssistantTool pathEcho = new AssistantTool() {
                @Override
                public AssistantToolDescriptor descriptor() {
                    return new AssistantToolDescriptor("get_my_task_summary",
                            "path echo", false, AssistantSchemas.object());
                }

                @Override
                public AssistantToolResult execute(com.fasterxml.jackson.databind.JsonNode arguments) {
                    com.fasterxml.jackson.databind.node.ObjectNode data = objectMapper.createObjectNode();
                    data.put("taskId", 1);
                    data.put("taskName", "报告 /secret.txt、/secrets、/z、/123、/.git 和 /custom dir/private directory");
                    return new AssistantToolResult(
                            "读取 /api/private/samples.png、/训练数据/样本.png、/数据目录 和 /custom dir", data,
                            null, null, 1L, null, null);
                }
            };
            AssistantChatService service = new AssistantChatService(model,
                    new AssistantToolRegistry(List.of(pathEcho)), mock(AssistantPreviewService.class),
                    mock(AssistantAuditService.class), quota(), sessions(), user(), objectMapper,
                    6, 5, 60, 1);

            AssistantChatResponse response = service.chat(null,
                    "分析 /datasets/private/sample.png、/secret.txt、/secrets、/z、/123、/.git、"
                            + "/api/private/samples.png、/custom dir/private directory、/数据目录 "
                            + "和 /custom dir/private sample.png 的样本");

            assertEquals("answered", response.status());
            assertEquals(2, server.bodies().size(), "tool evidence must reach a second model request");
            for (String body : server.bodies()) {
                assertFalse(body.contains("/datasets/private"), body);
                assertFalse(body.contains("/训练数据"), body);
                assertFalse(body.contains("/secret.txt"), body);
                assertFalse(body.contains("/secrets"), body);
                assertFalse(body.contains("/z"), body);
                assertFalse(body.contains("/123"), body);
                assertFalse(body.contains("/.git"), body);
                assertFalse(body.contains("/数据目录"), body);
                assertFalse(body.contains("/api/private"), body);
                assertFalse(body.contains("/custom dir"), body);
                assertTrue(body.contains("[已过滤路径]"), body);
                assertFalse(body.contains("sk-live-secret"), "API key must stay out of the body");
                assertFalse(body.contains("Authorization"), "header name must not leak into body");
            }
        }
    }

    @Test
    void validationEvidenceAndStableErrorCodeReachTheSecondModelRequest() throws Exception {
        try (FakeServer server = new FakeServer("validate_ldd_parameters",
                "{\"datasetId\":1,\"algorithmId\":2,\"customParams\":{}}")) {
            AssistantTool tool = tool("validate_ldd_parameters", data -> {
                data.put("valid", false);
                data.put("code", "PARAMETER_OUT_OF_RANGE");
                data.put("message", "batch_size 超出范围；原始路径 /secret/private.log");
                data.put("configFingerprint", "fp-abc123");
                data.put("sideEffect", "none");
                data.put("confirmationCreated", false);
                data.putObject("normalizedParameters")
                        .put("dataset", "cifar10")
                        .put("batch_size", 999)
                        .put("data_path", "/secret/private.log");
            });
            AssistantChatService service = service(server, tool);

            service.chat(null, "校验 batch_size=999 的 LDD 参数");

            assertEquals(2, server.bodies().size());
            String evidence = evidenceText(server.bodies().get(1));
            assertTrue(evidence.contains("\"valid\":false"), evidence);
            assertTrue(evidence.contains("PARAMETER_OUT_OF_RANGE"), evidence);
            assertTrue(evidence.contains("configFingerprint"), evidence);
            assertTrue(evidence.contains("fp-abc123"), evidence);
            assertTrue(evidence.contains("normalizedParameters"), evidence);
            assertTrue(evidence.contains("batch_size"), evidence);
            assertTrue(evidence.contains("cifar10"), evidence);
            assertFalse(evidence.contains("data_path"), evidence);
            assertFalse(evidence.contains("/secret"), evidence);
            assertFalse(evidence.contains("private.log"), evidence);
        }
    }

    @Test
    void validParametersReachTheSecondModelRequestWithStableCode() throws Exception {
        try (FakeServer server = new FakeServer("validate_ldd_parameters",
                "{\"datasetId\":1,\"algorithmId\":2,\"customParams\":{}}")) {
            AssistantTool tool = tool("validate_ldd_parameters", data -> {
                data.put("valid", true);
                data.put("code", "PARAMETERS_VALID");
                data.put("message", "参数校验通过");
                data.put("configFingerprint", "fp-valid");
                data.putObject("normalizedParameters")
                        .put("dataset", "cifar10")
                        .put("trigger_type", "SIG")
                        .put("batch_size", 128)
                        .put("seed", 123);
            });
            AssistantChatService service = service(server, tool);

            service.chat(null, "校验合法 LDD 参数");

            assertEquals(2, server.bodies().size());
            String evidence = evidenceText(server.bodies().get(1));
            assertTrue(evidence.contains("PARAMETERS_VALID"), evidence);
            assertTrue(evidence.contains("\"batch_size\":128"), evidence);
            assertTrue(evidence.contains("trigger_type"), evidence);
            assertTrue(evidence.contains("fp-valid"), evidence);
        }
    }

    @Test
    void preflightBlockersWarningsAndWorkerUnknownReachTheSecondModelRequest() throws Exception {
        try (FakeServer server = new FakeServer("get_my_task_summary",
                "{\"taskId\":1}")) {
            AssistantTool tool = tool("get_my_task_summary", data -> {
                data.put("taskId", 1);
                data.put("status", "pending");
                data.put("executorMode", "rabbit");
                data.put("executorRole", "api");
                data.put("executorConfiguration", "worker_unknown");
                data.putArray("blockers").addObject()
                        .put("code", "TASK_NOT_PENDING")
                        .put("message", "只有 pending 任务可以启动");
                data.putArray("warnings").addObject()
                        .put("code", "WORKER_CONFIGURATION_UNKNOWN")
                        .put("message", "Worker 执行器配置无法在此预检确认");
            });
            AssistantChatService service = service(server, tool);

            service.chat(null, "预检任务 1 能否启动");

            assertEquals(2, server.bodies().size());
            String evidence = evidenceText(server.bodies().get(1));
            assertTrue(evidence.contains("executorConfiguration"), evidence);
            assertTrue(evidence.contains("worker_unknown"), evidence);
            assertTrue(evidence.contains("WORKER_CONFIGURATION_UNKNOWN"), evidence);
            assertTrue(evidence.contains("blockers"), evidence);
            assertTrue(evidence.contains("warnings"), evidence);
            assertTrue(evidence.contains("TASK_NOT_PENDING"), evidence);
        }
    }

    private AssistantTool tool(String name, java.util.function.Consumer<
            com.fasterxml.jackson.databind.node.ObjectNode> fill) {
        return new AssistantTool() {
            @Override
            public AssistantToolDescriptor descriptor() {
                return new AssistantToolDescriptor(name, "test evidence", false,
                        AssistantSchemas.object());
            }

            @Override
            public AssistantToolResult execute(com.fasterxml.jackson.databind.JsonNode arguments) {
                com.fasterxml.jackson.databind.node.ObjectNode data = objectMapper.createObjectNode();
                fill.accept(data);
                return new AssistantToolResult("tool evidence", data, null, null, 1L, null, null);
            }
        };
    }

    private AssistantChatService service(FakeServer server, AssistantTool tool) {
        DeepSeekAssistantModel model = new DeepSeekAssistantModel(objectMapper, true,
                server.url(), "deepseek-chat", "sk-live-secret", 5, 128, 0);
        return new AssistantChatService(model, new AssistantToolRegistry(List.of(tool)),
                mock(AssistantPreviewService.class), mock(AssistantAuditService.class),
                quota(), sessions(), user(), objectMapper, 6, 5, 60, 1);
    }

    private String evidenceText(String requestBody) throws Exception {
        JsonNode messages = objectMapper.readTree(requestBody).path("messages");
        String content = messages.get(messages.size() - 1).path("content").asText();
        int start = content.indexOf('{');
        return start < 0 ? content : content.substring(start);
    }

    private AssistantQuotaService quota() {
        AssistantQuotaService quota = mock(AssistantQuotaService.class);
        when(quota.remaining()).thenReturn(50);
        return quota;
    }

    private AssistantSessionService sessions() {
        AssistantSessionService sessions = mock(AssistantSessionService.class);
        AssistantSession session = new AssistantSession();
        session.setSessionId(7L);
        when(sessions.getOrCreate(any(), anyLong(), any())).thenReturn(session);
        return sessions;
    }

    private CurrentUserService user() {
        CurrentUserService user = mock(CurrentUserService.class);
        when(user.requireStableIdentity()).thenReturn(new AuthenticatedUser(2L, "alice", false));
        return user;
    }

    private static final class FakeServer implements AutoCloseable {
        private final HttpServer server;
        private final java.util.List<String> bodies = new java.util.ArrayList<>();
        private final java.util.concurrent.atomic.AtomicInteger requests =
                new java.util.concurrent.atomic.AtomicInteger();

        FakeServer() throws IOException {
            this("get_my_task_summary", "{\"taskId\":1}");
        }

        FakeServer(String toolName, String arguments) throws IOException {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/chat/completions", exchange -> {
                String body = new String(exchange.getRequestBody().readAllBytes(),
                        StandardCharsets.UTF_8);
                synchronized (bodies) {
                    bodies.add(body);
                }
                int request = requests.getAndIncrement();
                String response = request == 0
                        ? "{\"model\":\"deepseek-flash\",\"choices\":[{\"message\":"
                        + "{\"content\":null,\"tool_calls\":[{\"id\":\"call_1\",\"type\":\"function\","
                        + "\"function\":{\"name\":\"" + toolName + "\","
                        + "\"arguments\":\"" + arguments.replace("\"", "\\\"") + "\"}}]}}]}"
                        : "{\"model\":\"deepseek-flash\",\"choices\":[{\"message\":"
                        + "{\"content\":\"已处理\"}}]}";
                byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, bytes.length);
                exchange.getResponseBody().write(bytes);
                exchange.close();
            });
            server.start();
        }

        String url() {
            return "http://127.0.0.1:" + server.getAddress().getPort() + "/chat/completions";
        }

        java.util.List<String> bodies() {
            synchronized (bodies) {
                return List.copyOf(bodies);
            }
        }

        @Override
        public void close() {
            server.stop(0);
        }
    }
}
