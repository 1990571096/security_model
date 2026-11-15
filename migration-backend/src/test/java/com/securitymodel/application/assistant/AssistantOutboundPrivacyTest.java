package com.securitymodel.application.assistant;

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
import java.util.concurrent.atomic.AtomicReference;

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
            AssistantChatService service = new AssistantChatService(model,
                    new AssistantToolRegistry(List.of()), mock(AssistantPreviewService.class),
                    mock(AssistantAuditService.class), quota(), sessions(), user(), objectMapper,
                    6, 5, 60, 1);

            AssistantChatResponse response = service.chat(null,
                    "分析 /datasets/private/sample.png 和 /训练数据/样本.png 的样本");

            assertEquals("answered", response.status());
            String body = server.body();
            assertFalse(body.contains("/datasets/private"), body);
            assertFalse(body.contains("/训练数据"), body);
            assertTrue(body.contains("[已过滤路径]"), body);
            assertFalse(body.contains("sk-live-secret"), "API key must stay out of the body");
            assertFalse(body.contains("Authorization"), "header name must not leak into body");
        }
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
        private final AtomicReference<String> body = new AtomicReference<>("");

        FakeServer() throws IOException {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/chat/completions", exchange -> {
                body.set(new String(exchange.getRequestBody().readAllBytes(),
                        StandardCharsets.UTF_8));
                byte[] bytes = ("{\"model\":\"deepseek-flash\",\"choices\":[{\"message\":"
                        + "{\"content\":\"已处理\"}}]}").getBytes(StandardCharsets.UTF_8);
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

        String body() {
            return body.get();
        }

        @Override
        public void close() {
            server.stop(0);
        }
    }
}
