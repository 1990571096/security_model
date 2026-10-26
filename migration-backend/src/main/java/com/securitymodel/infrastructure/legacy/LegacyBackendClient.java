package com.securitymodel.infrastructure.legacy;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * HTTP compatibility client for the legacy ELADMIN service.
 *
 * <p>The legacy service may return a null header key for its HTTP status line.
 * JDK HttpURLConnection exposes that entry without trying to turn it into a
 * Spring header, which makes this boundary tolerant during the migration.</p>
 */
@Component
public class LegacyBackendClient {

    private static final Set<String> HOP_BY_HOP_HEADERS = Set.of(
            "connection",
            "keep-alive",
            "proxy-authenticate",
            "proxy-authorization",
            "te",
            "trailer",
            "transfer-encoding",
            "upgrade"
    );

    private final String baseUrl;
    private final ObjectMapper objectMapper;

    public LegacyBackendClient(
            @Value("${security-model.legacy.base-url:http://localhost:8000}") String baseUrl,
            ObjectMapper objectMapper) {
        this.baseUrl = baseUrl.replaceAll("/+$", "");
        this.objectMapper = objectMapper;
    }

    public ResponseEntity<byte[]> exchange(
            HttpMethod method,
            String path,
            String authorization,
            Object body) {
        try {
            byte[] requestBody = body == null ? null : objectMapper.writeValueAsBytes(body);
            return execute(method, path, authorization, "application/json", requestBody);
        } catch (IOException | RuntimeException exception) {
            return gatewayError(exception);
        }
    }

    public ResponseEntity<byte[]> exchangeBytes(
            HttpMethod method,
            String path,
            String authorization,
            String contentType,
            byte[] body) {
        try {
            return execute(method, path, authorization, contentType, body);
        } catch (IOException | RuntimeException exception) {
            return gatewayError(exception);
        }
    }

    private ResponseEntity<byte[]> execute(
            HttpMethod method,
            String path,
            String authorization,
            String contentType,
            byte[] body) throws IOException {
        URI targetUri = URI.create(baseUrl + path);
        HttpURLConnection connection = (HttpURLConnection) targetUri.toURL().openConnection();
        connection.setRequestMethod(method.name());
        connection.setConnectTimeout(5_000);
        connection.setReadTimeout(30_000);
        connection.setInstanceFollowRedirects(false);
        connection.setUseCaches(false);

        if (authorization != null && !authorization.isBlank()) {
            connection.setRequestProperty(HttpHeaders.AUTHORIZATION, authorization);
        }
        if (body != null && body.length > 0) {
            connection.setDoOutput(true);
            if (contentType != null && !contentType.isBlank()) {
                connection.setRequestProperty(HttpHeaders.CONTENT_TYPE, contentType);
            }
            connection.setFixedLengthStreamingMode(body.length);
            connection.getOutputStream().write(body);
        }

        try {
            int status = connection.getResponseCode();
            HttpHeaders headers = copyResponseHeaders(connection.getHeaderFields());
            byte[] responseBody = readResponseBody(connection, status);
            return ResponseEntity.status(HttpStatusCode.valueOf(status))
                    .headers(headers)
                    .body(responseBody);
        } finally {
            connection.disconnect();
        }
    }

    private byte[] readResponseBody(HttpURLConnection connection, int status) throws IOException {
        InputStream stream = status >= 400
                ? connection.getErrorStream()
                : connection.getInputStream();
        if (stream == null) {
            return new byte[0];
        }
        try (InputStream input = stream;
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            input.transferTo(output);
            return output.toByteArray();
        }
    }

    private HttpHeaders copyResponseHeaders(Map<String, List<String>> source) {
        HttpHeaders headers = new HttpHeaders();
        if (source == null) {
            return headers;
        }
        for (Map.Entry<String, List<String>> entry : source.entrySet()) {
            String name = entry.getKey();
            if (name == null || name.isBlank() || HOP_BY_HOP_HEADERS.contains(name.toLowerCase())) {
                continue;
            }
            List<String> values = entry.getValue();
            if (values == null) {
                continue;
            }
            try {
                headers.put(name, values.stream().filter(value -> value != null).toList());
            } catch (IllegalArgumentException ignored) {
                // Ignore malformed legacy headers at the compatibility boundary.
            }
        }
        return headers;
    }

    private ResponseEntity<byte[]> gatewayError(Exception exception) {
        String message = "Legacy backend unavailable: " + exception.getMessage();
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                .contentType(org.springframework.http.MediaType.TEXT_PLAIN)
                .body(message.getBytes(StandardCharsets.UTF_8));
    }
}
