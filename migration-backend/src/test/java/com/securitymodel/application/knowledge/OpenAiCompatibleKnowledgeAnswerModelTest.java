package com.securitymodel.application.knowledge;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OpenAiCompatibleKnowledgeAnswerModelTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void disabledOrIncompleteConfigurationNeverCallsHttp() {
        OpenAiCompatibleKnowledgeAnswerModel disabled = new OpenAiCompatibleKnowledgeAnswerModel(
                objectMapper, false, "http://127.0.0.1:1/v1", "m", "key", 2, 1, 600);
        assertEquals("model_disabled", disabled.answer("q", List.of()).status());

        OpenAiCompatibleKnowledgeAnswerModel incomplete = new OpenAiCompatibleKnowledgeAnswerModel(
                objectMapper, true, "", "m", "", 2, 1, 600);
        assertEquals("model_not_configured", incomplete.answer("q", List.of()).status());
    }

    @Test
    void sendsOnlyWhitelistedPayloadAndParsesAnswer() throws Exception {
        try (FakeServer server = new FakeServer(1, (attempt, request) ->
                new Response(200, "{\"choices\":[{\"message\":{\"content\":\"依据 [C1] 回答\"}}]}"))) {
            OpenAiCompatibleKnowledgeAnswerModel model = new OpenAiCompatibleKnowledgeAnswerModel(
                    objectMapper, true, server.url(), "model-x", "sk-test-key", 5, 1, 600);
            KnowledgeCitation citation = citation("C1", "F:\\Study\\secret\\data.txt sk-secret12345678");

            KnowledgeAnswerModel.ModelResult result = model.answer("租约问题", List.of(citation));

            assertEquals("answered", result.status());
            assertEquals("依据 [C1] 回答", result.answer());
            assertEquals(1, result.attempts());
            String body = server.requests().get(0).body();
            assertFalse(body.contains("F:\\\\Study"), "path must be redacted before send");
            assertFalse(body.contains("sk-secret"), "credential must be redacted before send");
            assertFalse(body.contains("sdd:05"), "source key must not be sent");
            assertFalse(body.contains("sk-test-key"), "key must stay in the header, never the body");
        }
    }

    @Test
    void retriesOnceOnServerErrorThenSucceeds() throws Exception {
        try (FakeServer server = new FakeServer(2, (attempt, request) -> attempt == 1
                ? new Response(500, "{}")
                : new Response(200, "{\"choices\":[{\"message\":{\"content\":\"ok [C1]\"}}]}"))) {
            OpenAiCompatibleKnowledgeAnswerModel model = new OpenAiCompatibleKnowledgeAnswerModel(
                    objectMapper, true, server.url(), "model-x", "key", 5, 1, 600);

            KnowledgeAnswerModel.ModelResult result = model.answer("q", List.of(citation("C1", "body")));

            assertEquals("answered", result.status());
            assertEquals(2, result.attempts());
            assertEquals(2, server.requests().size());
        }
    }

    @Test
    void clientTimeoutFallsBackWithoutHanging() throws Exception {
        try (FakeServer server = new FakeServer(1, (attempt, request) -> {
            Thread.sleep(1500);
            return new Response(200, "{\"choices\":[{\"message\":{\"content\":\"late\"}}]}");
        })) {
            OpenAiCompatibleKnowledgeAnswerModel model = new OpenAiCompatibleKnowledgeAnswerModel(
                    objectMapper, true, server.url(), "model-x", "key", 1, 0, 600);

            long started = System.nanoTime();
            KnowledgeAnswerModel.ModelResult result = model.answer("q", List.of(citation("C1", "body")));

            assertEquals("model_timeout", result.status());
            assertTrue(TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - started) < 4);
        }
    }

    @Test
    void nonRetryable4xxDoesNotCallTwice() throws Exception {
        try (FakeServer server = new FakeServer(1, (attempt, request) -> new Response(401, "{}"))) {
            OpenAiCompatibleKnowledgeAnswerModel model = new OpenAiCompatibleKnowledgeAnswerModel(
                    objectMapper, true, server.url(), "model-x", "key", 5, 1, 600);
            KnowledgeAnswerModel.ModelResult result = model.answer("q", List.of(citation("C1", "body")));
            assertEquals("model_unavailable", result.status());
            assertEquals(1, result.attempts());
        }
    }

    private KnowledgeCitation citation(String id, String snippet) {
        return new KnowledgeCitation(id, 1L, "sdd", "sdd:05-decisions.md", "标题", 1,
                "R2", 0, 1, 2, snippet, 1.0, "shared", null);
    }

    private record Request(String headers, String body) {
    }

    private record Response(int status, String body) {
    }

    private interface Responder {
        Response respond(int attempt, Request request) throws Exception;
    }

    private static final class FakeServer implements AutoCloseable {
        private final ServerSocket serverSocket;
        private final Thread thread;
        private final List<Request> requests = Collections.synchronizedList(new ArrayList<>());
        private final CountDownLatch done = new CountDownLatch(1);

        FakeServer(int expectedRequests, Responder responder) throws IOException {
            serverSocket = new ServerSocket(0);
            thread = new Thread(() -> {
                try {
                    for (int attempt = 1; attempt <= expectedRequests; attempt++) {
                        try (Socket socket = serverSocket.accept()) {
                            socket.setSoTimeout(5000);
                            Request request = read(socket.getInputStream());
                            requests.add(request);
                            Response response = responder.respond(attempt, request);
                            write(socket.getOutputStream(), response);
                        }
                    }
                } catch (Exception ignored) {
                    // socket closed by the test after timeout is expected
                } finally {
                    done.countDown();
                }
            }, "knowledge-model-test-server");
            thread.setDaemon(true);
            thread.start();
        }

        String url() {
            return "http://127.0.0.1:" + serverSocket.getLocalPort() + "/v1/chat/completions";
        }

        List<Request> requests() {
            return requests;
        }

        private Request read(InputStream input) throws IOException {
            ByteArrayOutputStream headerBytes = new ByteArrayOutputStream();
            int current;
            while ((current = input.read()) >= 0) {
                headerBytes.write(current);
                if (endsWithHeaderTerminator(headerBytes.toByteArray())) {
                    break;
                }
            }
            String headers = headerBytes.toString(StandardCharsets.UTF_8);
            int contentLength = 0;
            for (String line : headers.split("\r\n")) {
                if (line.toLowerCase().startsWith("content-length:")) {
                    contentLength = Integer.parseInt(line.substring(line.indexOf(':') + 1).trim());
                }
            }
            byte[] body = new byte[contentLength];
            int read = 0;
            while (read < contentLength) {
                int count = input.read(body, read, contentLength - read);
                if (count < 0) {
                    break;
                }
                read += count;
            }
            return new Request(headers, new String(body, StandardCharsets.UTF_8));
        }

        private boolean endsWithHeaderTerminator(byte[] bytes) {
            return bytes.length >= 4
                    && bytes[bytes.length - 4] == '\r'
                    && bytes[bytes.length - 3] == '\n'
                    && bytes[bytes.length - 2] == '\r'
                    && bytes[bytes.length - 1] == '\n';
        }

        private void write(OutputStream output, Response response) throws IOException {
            byte[] body = response.body().getBytes(StandardCharsets.UTF_8);
            String head = "HTTP/1.1 " + response.status() + " OK\r\n"
                    + "Content-Type: application/json\r\n"
                    + "Content-Length: " + body.length + "\r\n"
                    + "Connection: close\r\n\r\n";
            output.write(head.getBytes(StandardCharsets.UTF_8));
            output.write(body);
            output.flush();
        }

        @Override
        public void close() throws Exception {
            serverSocket.close();
            done.await(4, TimeUnit.SECONDS);
        }
    }
}
