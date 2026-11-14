package com.securitymodel.application.knowledge;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
 * Generic OpenAI-compatible HTTP adapter. It is disabled unless the operator explicitly
 * supplies a base URL, model and key. No provider-specific data is hard-coded; actual
 * provider/budget/data-retention approval remains a human decision.
 */
@Component
@ConditionalOnProperty(prefix = "security-model.knowledge.model", name = "provider",
        havingValue = "openai-compatible", matchIfMissing = true)
public class OpenAiCompatibleKnowledgeAnswerModel implements KnowledgeAnswerModel {

    private static final Logger log = LoggerFactory.getLogger(OpenAiCompatibleKnowledgeAnswerModel.class);

    private final ObjectMapper objectMapper;
    private final boolean enabled;
    private final String baseUrl;
    private final String model;
    private final String apiKey;
    private final Duration timeout;
    private final int maxRetries;
    private final int maxCitationChars;
    private final HttpClient httpClient;

    public OpenAiCompatibleKnowledgeAnswerModel(
            ObjectMapper objectMapper,
            @Value("${security-model.knowledge.model.enabled:false}") boolean enabled,
            @Value("${security-model.knowledge.model.base-url:}") String baseUrl,
            @Value("${security-model.knowledge.model.name:}") String model,
            @Value("${security-model.knowledge.model.api-key:}") String apiKey,
            @Value("${security-model.knowledge.model.timeout-seconds:30}") long timeoutSeconds,
            @Value("${security-model.knowledge.model.max-retries:1}") int maxRetries,
            @Value("${security-model.knowledge.model.max-citation-chars:600}") int maxCitationChars) {
        this.objectMapper = objectMapper;
        this.enabled = enabled;
        this.baseUrl = baseUrl == null ? "" : baseUrl.trim();
        this.model = model == null ? "" : model.trim();
        this.apiKey = apiKey == null ? "" : apiKey.trim();
        this.timeout = Duration.ofSeconds(Math.max(1, timeoutSeconds));
        this.maxRetries = Math.max(0, Math.min(1, maxRetries));
        this.maxCitationChars = Math.max(120, maxCitationChars);
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(Math.min(5, Math.max(1, timeoutSeconds))))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    @Override
    public ModelResult answer(String question, List<KnowledgeCitation> citations) {
        if (!enabled) {
            return KnowledgeAnswerModel.ModelResult.disabled("外部模型未启用");
        }
        if (baseUrl.isBlank() || model.isBlank() || apiKey.isBlank()) {
            return new KnowledgeAnswerModel.ModelResult(
                    "model_not_configured", "外部模型配置不完整", model, 0);
        }
        URI uri;
        try {
            uri = URI.create(baseUrl);
            if (!"http".equalsIgnoreCase(uri.getScheme()) && !"https".equalsIgnoreCase(uri.getScheme())) {
                return new KnowledgeAnswerModel.ModelResult(
                        "model_not_configured", "外部模型地址协议不受支持", model, 0);
            }
        } catch (RuntimeException exception) {
            return new KnowledgeAnswerModel.ModelResult(
                    "model_not_configured", "外部模型地址无效", model, 0);
        }

        String payload = KnowledgePromptBuilder
                .build(objectMapper, model, question, citations, maxCitationChars).toString();
        int attempts = 0;
        for (int attempt = 0; attempt <= maxRetries; attempt++) {
            attempts++;
            try {
                HttpRequest request = HttpRequest.newBuilder(uri)
                        .timeout(timeout)
                        .header("Content-Type", "application/json")
                        .header("Authorization", "Bearer " + apiKey)
                        .POST(HttpRequest.BodyPublishers.ofString(payload))
                        .build();
                HttpResponse<String> response = httpClient.send(request,
                        HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() >= 200 && response.statusCode() < 300) {
                    String answer = parseAnswer(response.body());
                    if (answer == null || answer.isBlank()) {
                        return new KnowledgeAnswerModel.ModelResult(
                                "model_invalid_response", "外部模型返回空答案", model, attempts);
                    }
                    return new KnowledgeAnswerModel.ModelResult("answered", answer, model, attempts);
                }
                if ((response.statusCode() == 429 || response.statusCode() >= 500)
                        && attempt < maxRetries) {
                    continue;
                }
                log.warn("知识回答模型返回非成功状态: status={}, attempt={}",
                        response.statusCode(), attempts);
                return new KnowledgeAnswerModel.ModelResult(
                        "model_unavailable", "外部模型暂不可用", model, attempts);
            } catch (HttpTimeoutException exception) {
                if (attempt < maxRetries) {
                    continue;
                }
                log.warn("知识回答模型超时: attempt={}", attempts);
                return new KnowledgeAnswerModel.ModelResult(
                        "model_timeout", "外部模型超时", model, attempts);
            } catch (IOException exception) {
                if (attempt < maxRetries) {
                    continue;
                }
                log.warn("知识回答模型网络失败: attempt={}, reason={}", attempts,
                        exception.getClass().getSimpleName());
                return new KnowledgeAnswerModel.ModelResult(
                        "model_unavailable", "外部模型网络不可用", model, attempts);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                return new KnowledgeAnswerModel.ModelResult(
                        "model_unavailable", "外部模型调用被中断", model, attempts);
            } catch (RuntimeException exception) {
                log.warn("知识回答模型调用失败: attempt={}, reason={}", attempts,
                        exception.getClass().getSimpleName());
                return new KnowledgeAnswerModel.ModelResult(
                        "model_unavailable", "外部模型调用失败", model, attempts);
            }
        }
        return new KnowledgeAnswerModel.ModelResult(
                "model_unavailable", "外部模型暂不可用", model, attempts);
    }

    private String parseAnswer(String body) {
        try {
            JsonNode root = objectMapper.readTree(body);
            JsonNode content = root.path("choices").path(0).path("message").path("content");
            return content.isTextual() ? content.asText() : null;
        } catch (Exception exception) {
            return null;
        }
    }

    /** Test/status hook; never exposes the key or full URL. */
    public boolean isEnabled() {
        return enabled && !baseUrl.isBlank() && !model.isBlank() && !apiKey.isBlank();
    }

    public String getModel() {
        return model;
    }
}
