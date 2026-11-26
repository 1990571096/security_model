package com.securitymodel.application.assistant;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DeepSeekAssistantModelTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void incompleteConfigurationNeverCallsHttp() {
        DeepSeekAssistantModel disabled = new DeepSeekAssistantModel(
                objectMapper, false, "http://127.0.0.1:1", "deepseek-chat", "key", 2, 128, 0);
        assertEquals("model_disabled", disabled.respond(request()).status());

        DeepSeekAssistantModel incomplete = new DeepSeekAssistantModel(
                objectMapper, true, "", "deepseek-chat", "", 2, 128, 0);
        assertEquals("model_not_configured", incomplete.respond(request()).status());
    }

    @Test
    void parsesToolCallAndNeverPutsKeyInRequestBody() throws Exception {
        try (FakeServer server = new FakeServer("""
                {"model":"deepseek-flash","choices":[{"finish_reason":"tool_calls","message":{
                "content":null,"tool_calls":[{"id":"call_1","type":"function","function":{
                "name":"get_my_task_summary","arguments":"{\\"task_id\\":42}"}}]}}]}
                """)) {
            DeepSeekAssistantModel model = model(server.url(), "sk-test-key");
            AssistantModel.ModelReply reply = model.respond(request());

            assertEquals("ok", reply.status());
            assertNotNull(reply.toolCall());
            assertEquals("get_my_task_summary", reply.toolCall().toolName());
            assertEquals(42, reply.toolCall().arguments().path("task_id").asInt());
            assertEquals("deepseek-flash", reply.model());
            String body = server.body();
            assertTrue(body.contains("\"tools\""));
            assertTrue(body.contains("get_my_task_summary"));
            assertFalse(body.contains("sk-test-key"), "key must stay in the Authorization header");
            assertFalse(body.contains("Authorization"), "credentials must not appear in body");
        }
    }

    @Test
    void parsesPlainAnswer() throws Exception {
        try (FakeServer server = new FakeServer("""
                {"model":"deepseek-flash","choices":[{"message":{"content":"任务当前是 pending。"}}]}
                """)) {
            DeepSeekAssistantModel model = model(server.url(), "key");
            AssistantModel.ModelReply reply = model.respond(request());

            assertEquals("ok", reply.status());
            assertEquals("任务当前是 pending。", reply.answer());
            assertEquals("deepseek-flash", reply.model());
        }
    }

    @Test
    void authFailureIsReportedWithoutRetry() throws Exception {
        try (FakeServer server = new FakeServer("{\"error\":{\"message\":\"bad key\"}}", 401)) {
            DeepSeekAssistantModel model = model(server.url(), "key");
            AssistantModel.ModelReply reply = model.respond(request());

            assertEquals("model_auth_failed", reply.status());
            assertEquals(1, server.requestCount());
        }
    }

    @Test
    void timeoutReturnsStableStatus() throws Exception {
        try (FakeServer server = new FakeServer(
                "{\"choices\":[{\"message\":{\"content\":\"late\"}}]}", 200, 1500)) {
            DeepSeekAssistantModel model = new DeepSeekAssistantModel(
                    objectMapper, true, server.url(), "deepseek-chat", "key", 1, 128, 0);
            long started = System.nanoTime();
            AssistantModel.ModelReply reply = model.respond(request());

            assertEquals("model_timeout", reply.status());
            assertTrue(System.nanoTime() - started < 3_000_000_000L);
        }
    }

    @Test
    void retriesOn429ThenReturnsTheSuccessfulAnswer() throws Exception {
        try (SequenceServer server = new SequenceServer(429, "{\"error\":\"rate limited\"}",
                "{\"choices\":[{\"message\":{\"content\":\"重试后成功\"}}]}")) {
            DeepSeekAssistantModel model = new DeepSeekAssistantModel(
                    objectMapper, true, server.url(), "deepseek-chat", "key", 5, 128, 1);
            AssistantModel.ModelReply reply = model.respond(request());

            assertEquals("ok", reply.status());
            assertEquals("重试后成功", reply.answer());
            assertEquals(2, server.requestCount());
        }
    }

    @Test
    void serverErrorWithoutRetryReturnsStableUnavailableStatus() throws Exception {
        try (FakeServer server = new FakeServer("{\"error\":\"boom\"}", 500)) {
            DeepSeekAssistantModel model = model(server.url(), "key");
            AssistantModel.ModelReply reply = model.respond(request());

            assertEquals("model_unavailable", reply.status());
            assertEquals(1, server.requestCount());
        }
    }

    @Test
    void malformedResponseReturnsInvalidResponseStatus() throws Exception {
        try (FakeServer server = new FakeServer("{\"choices\":[{\"message\":", 200)) {
            DeepSeekAssistantModel model = model(server.url(), "key");
            AssistantModel.ModelReply reply = model.respond(request());

            assertEquals("model_invalid_response", reply.status());
        }
    }

    private DeepSeekAssistantModel model(String url, String key) {
        return new DeepSeekAssistantModel(objectMapper, true, url, "deepseek-chat", key, 5, 128, 0);
    }

    private AssistantModel.ModelRequest request() {
        ObjectNode schema = objectMapper.createObjectNode();
        schema.put("type", "object");
        schema.putObject("properties").putObject("task_id").put("type", "integer");
        schema.putArray("required").add("task_id");
        return new AssistantModel.ModelRequest(
                "查询 42 号任务",
                List.of(new AssistantModel.ModelMessage("user", "查询 42 号任务")),
                List.of(new AssistantToolDescriptor("get_my_task_summary", "查询任务摘要", false, schema)),
                2, 5000);
    }

    private static final class SequenceServer implements AutoCloseable {
        private final HttpServer server;
        private final AtomicInteger requests = new AtomicInteger();

        SequenceServer(int firstStatus, String firstBody, String secondBody) throws IOException {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/chat/completions", exchange -> {
                int request = requests.getAndIncrement();
                String body = request == 0 ? firstBody : secondBody;
                int status = request == 0 ? firstStatus : 200;
                byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(status, bytes.length);
                exchange.getResponseBody().write(bytes);
                exchange.close();
            });
            server.start();
        }

        String url() {
            return "http://127.0.0.1:" + server.getAddress().getPort() + "/chat/completions";
        }

        int requestCount() {
            return requests.get();
        }

        @Override
        public void close() {
            server.stop(0);
        }
    }

    private static final class FakeServer implements AutoCloseable {
        private final HttpServer server;
        private final AtomicReference<String> body = new AtomicReference<>("");
        private final AtomicInteger requests = new AtomicInteger();

        FakeServer(String response) throws IOException {
            this(response, 200, 0);
        }

        FakeServer(String response, int status) throws IOException {
            this(response, status, 0);
        }

        FakeServer(String response, int status, long delayMillis) throws IOException {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/chat/completions", exchange -> {
                requests.incrementAndGet();
                body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                if (delayMillis > 0) {
                    try {
                        Thread.sleep(delayMillis);
                    } catch (InterruptedException exception) {
                        Thread.currentThread().interrupt();
                    }
                }
                byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(status, bytes.length);
                exchange.getResponseBody().write(bytes);
                exchange.close();
            });
            server.start();
        }

        String url() {
            return "http://127.0.0.1:" + server.getAddress().getPort() + "/chat/completions";
        }

        String body() {
            return body.get();
        }

        int requestCount() {
            return requests.get();
        }

        @Override
        public void close() {
            server.stop(0);
        }
    }
}
