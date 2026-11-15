package com.securitymodel.application.assistant;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.securitymodel.infrastructure.config.LocalApiKeyResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.List;

/**
 * DeepSeek/OpenAI-compatible assistant adapter. The model receives only the sanitized
 * question, allow-listed tool schemas and tool-result evidence. It never receives an API
 * key, database credentials, file paths or another user's data. A returned tool name is
 * still executed by the server through {@link AssistantToolRegistry}; write tools always
 * become a preview and require a separate confirmation.
 */
@Component
@ConditionalOnProperty(prefix = "security-model.assistant.model", name = "provider",
        havingValue = "deepseek")
public class DeepSeekAssistantModel implements AssistantModel {

    private static final Logger log = LoggerFactory.getLogger(DeepSeekAssistantModel.class);
    private static final String SYSTEM_PROMPT = """
            你是 security_model 平台的任务助手。你只能使用服务端提供的工具获取业务事实；
            不要编造任务状态、指标、路径或用户信息。创建、启动、重试、取消、导出等写操作
            只能生成预览，必须由用户另行确认后才执行。回答使用简体中文，简洁说明依据。
            """;

    private final ObjectMapper objectMapper;
    private final boolean enabled;
    private final String endpoint;
    private final String model;
    private final String apiKey;
    private final long timeoutMillis;
    private final int maxOutputTokens;
    private final int maxRetries;
    private final HttpClient httpClient;

    public DeepSeekAssistantModel(ObjectMapper objectMapper,
                                  boolean enabled,
                                  String endpoint,
                                  String model,
                                  String apiKey,
                                  long timeoutSeconds,
                                  int maxOutputTokens,
                                  int maxRetries) {
        this(objectMapper, enabled, endpoint, model, apiKey, timeoutSeconds,
                maxOutputTokens, maxRetries, "");
    }

    @Autowired
    public DeepSeekAssistantModel(
            ObjectMapper objectMapper,
            @Value("${security-model.assistant.model.enabled:false}") boolean enabled,
            @Value("${security-model.assistant.model.base-url:}") String endpoint,
            @Value("${security-model.assistant.model.name:}") String model,
            @Value("${security-model.assistant.model.api-key:}") String apiKey,
            @Value("${security-model.assistant.model.api-key-file:}") String apiKeyFile,
            @Value("${security-model.assistant.model.timeout-seconds:60}") long timeoutSeconds,
            @Value("${security-model.assistant.model.max-output-tokens:1024}") int maxOutputTokens,
            @Value("${security-model.assistant.model.max-retries:0}") int maxRetries) {
        this(objectMapper, enabled, endpoint, model,
                LocalApiKeyResolver.resolve(apiKey, apiKeyFile),
                timeoutSeconds, maxOutputTokens, maxRetries, "");
    }

    private DeepSeekAssistantModel(ObjectMapper objectMapper, boolean enabled, String endpoint,
                                   String model, String apiKey, long timeoutSeconds,
                                   int maxOutputTokens, int maxRetries, String ignored) {
        this.objectMapper = objectMapper;
        this.enabled = enabled;
        this.endpoint = endpoint == null ? "" : endpoint.trim();
        this.model = model == null ? "" : model.trim();
        this.apiKey = apiKey == null ? "" : apiKey.trim();
        this.timeoutMillis = Duration.ofSeconds(Math.max(1, Math.min(180, timeoutSeconds))).toMillis();
        this.maxOutputTokens = Math.max(64, Math.min(4096, maxOutputTokens));
        this.maxRetries = Math.max(0, Math.min(1, maxRetries));
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(8))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    @Override
    public ModelReply respond(ModelRequest request) {
        if (!enabled) {
            return ModelReply.unavailable("model_disabled", "助手模型未启用");
        }
        if (endpoint.isBlank() || model.isBlank() || apiKey.isBlank()) {
            return ModelReply.unavailable("model_not_configured", "助手模型配置不完整");
        }
        URI uri;
        try {
            uri = URI.create(endpoint);
            if (!"http".equalsIgnoreCase(uri.getScheme())
                    && !"https".equalsIgnoreCase(uri.getScheme())) {
                return ModelReply.unavailable("model_not_configured", "助手模型地址协议不受支持");
            }
        } catch (RuntimeException exception) {
            return ModelReply.unavailable("model_not_configured", "助手模型地址无效");
        }

        String payload;
        try {
            payload = buildPayload(request).toString();
        } catch (RuntimeException exception) {
            log.warn("助手模型请求构造失败: {}", exception.getClass().getSimpleName());
            return ModelReply.unavailable("model_invalid_request", "助手模型请求构造失败");
        }

        long budgetMillis = Math.max(1, Math.min(timeoutMillis, request.timeoutMillis()));
        int attempts = 0;
        for (int attempt = 0; attempt <= maxRetries; attempt++) {
            attempts++;
            try {
                HttpRequest httpRequest = HttpRequest.newBuilder(uri)
                        .timeout(Duration.ofMillis(budgetMillis))
                        .header("Content-Type", "application/json")
                        .header("Authorization", "Bearer " + apiKey)
                        .POST(HttpRequest.BodyPublishers.ofString(payload))
                        .build();
                HttpResponse<String> response = httpClient.send(httpRequest,
                        HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() >= 200 && response.statusCode() < 300) {
                    ModelReply reply = parseReply(response.body());
                    if (reply != null) {
                        return reply;
                    }
                    return ModelReply.unavailable("model_invalid_response", "助手模型返回格式不可校验");
                }
                if ((response.statusCode() == 429 || response.statusCode() >= 500)
                        && attempt < maxRetries) {
                    continue;
                }
                log.warn("助手模型返回非成功状态: status={}, attempt={}",
                        response.statusCode(), attempts);
                if (response.statusCode() == 401 || response.statusCode() == 403) {
                    return ModelReply.unavailable("model_auth_failed", "助手模型认证失败");
                }
                return ModelReply.unavailable("model_unavailable", "助手模型暂不可用");
            } catch (HttpTimeoutException exception) {
                if (attempt < maxRetries) {
                    continue;
                }
                log.warn("助手模型超时: attempt={}", attempts);
                return ModelReply.unavailable("model_timeout", "助手模型超时");
            } catch (IOException exception) {
                if (attempt < maxRetries) {
                    continue;
                }
                log.warn("助手模型网络失败: attempt={}, reason={}", attempts,
                        exception.getClass().getSimpleName());
                return ModelReply.unavailable("model_unavailable", "助手模型网络不可用");
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                return ModelReply.unavailable("model_unavailable", "助手模型调用被中断");
            } catch (RuntimeException exception) {
                log.warn("助手模型调用失败: attempt={}, reason={}", attempts,
                        exception.getClass().getSimpleName());
                return ModelReply.unavailable("model_unavailable", "助手模型调用失败");
            }
        }
        return ModelReply.unavailable("model_unavailable", "助手模型暂不可用");
    }

    private ObjectNode buildPayload(ModelRequest request) {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("model", model);
        root.put("stream", false);
        root.put("temperature", 0.2);
        root.put("max_tokens", maxOutputTokens);

        ArrayNode messages = root.putArray("messages");
        ObjectNode system = messages.addObject();
        system.put("role", "system");
        system.put("content", SYSTEM_PROMPT + "\n本轮最多还能调用 "
                + request.remainingToolCalls() + " 次工具。");
        for (ModelMessage message : request.messages()) {
            ObjectNode item = messages.addObject();
            if ("tool".equals(message.role())) {
                item.put("role", "user");
                item.put("content", "服务端工具结果：\n" + nullSafe(message.content()));
            } else {
                item.put("role", "assistant".equals(message.role()) ? "assistant" : "user");
                item.put("content", nullSafe(message.content()));
            }
        }

        if (request.tools() != null && !request.tools().isEmpty()) {
            ArrayNode tools = root.putArray("tools");
            for (AssistantToolDescriptor descriptor : request.tools()) {
                ObjectNode tool = tools.addObject();
                tool.put("type", "function");
                ObjectNode function = tool.putObject("function");
                function.put("name", descriptor.name());
                function.put("description", descriptor.description());
                JsonNode schema = descriptor.inputSchema();
                function.set("parameters", schema == null
                        ? objectMapper.createObjectNode().put("type", "object")
                        : schema.deepCopy());
            }
            root.put("tool_choice", "auto");
        }
        return root;
    }

    private ModelReply parseReply(String body) {
        try {
            JsonNode root = objectMapper.readTree(body);
            JsonNode choice = root.path("choices").path(0);
            JsonNode message = choice.path("message");
            JsonNode toolCalls = message.path("tool_calls");
            if (toolCalls.isArray() && !toolCalls.isEmpty()) {
                JsonNode function = toolCalls.get(0).path("function");
                String name = function.path("name").asText("");
                if (name.isBlank()) {
                    return ModelReply.unavailable("model_invalid_response", "助手模型返回空工具名");
                }
                JsonNode arguments = objectMapper.createObjectNode();
                String raw = function.path("arguments").asText("");
                if (!raw.isBlank()) {
                    try {
                        arguments = objectMapper.readTree(raw);
                    } catch (RuntimeException exception) {
                        return ModelReply.unavailable("model_invalid_response",
                                "助手模型返回工具参数不是合法 JSON");
                    }
                }
                String returnedModel = root.path("model").asText(model);
                return ModelReply.toolCall(name, arguments, returnedModel);
            }
            JsonNode content = message.path("content");
            if (content.isTextual() && !content.asText().isBlank()) {
                String returnedModel = root.path("model").asText(model);
                return ModelReply.answer(content.asText().trim(), returnedModel);
            }
            return null;
        } catch (Exception exception) {
            return null;
        }
    }

    private String nullSafe(String value) {
        return value == null ? "" : value;
    }

    /** Status hook; never exposes the key or full endpoint. */
    public boolean isConfigured() {
        return enabled && !endpoint.isBlank() && !model.isBlank() && !apiKey.isBlank();
    }

    public String getModel() {
        return model;
    }

    public List<String> exposedTools(ModelRequest request) {
        return request.tools() == null ? List.of()
                : request.tools().stream().map(AssistantToolDescriptor::name).toList();
    }
}
