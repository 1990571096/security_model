package com.securitymodel.application.knowledge;

import java.util.regex.Pattern;

/**
 * Minimal, deterministic outbound redaction. It is applied both at ingestion (shared
 * technical docs must not expose another user's local layout) and before any external
 * model request. It deliberately over-redacts rather than risk leaking a credential.
 */
public final class KnowledgeSanitizer {

    private static final Pattern WINDOWS_PATH =
            Pattern.compile("(?i)\\b[A-Z]:[/\\\\][^\\s，。；、）)]+");
    private static final Pattern UNC_PATH =
            Pattern.compile("\\\\\\\\[^\\s，。；、）)]+");
    private static final Pattern UNIX_PATH =
            Pattern.compile("(?i)(?<![A-Za-z0-9])/(?:home|users|tmp|var|opt|root|etc|usr|mnt|media|private|proc|sys)"
                    + "(?:/[A-Za-z0-9._-]+)+");
    private static final Pattern BEARER =
            Pattern.compile("(?i)bearer\\s+[A-Za-z0-9._~+/-]+=*");
    private static final Pattern JSON_CREDENTIAL =
            Pattern.compile("(?i)[\"'](?:api[_-]?key|access[_-]?key|secret[_-]?key|app[_-]?key|token"
                    + "|password|passwd|secret|authorization|credential)[\"']\\s*:\\s*[\"'][^\"']*[\"']");
    private static final Pattern API_KEY =
            Pattern.compile("(?i)(?:sk-|api[_-]?key[=: ]+|token[=: ]+|password[=: ]+)[A-Za-z0-9._~+/-]{8,}");
    private static final Pattern JWT =
            Pattern.compile("eyJ[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]{8,}");
    private static final Pattern EMAIL =
            Pattern.compile("(?i)\\b[A-Z0-9._%+-]+@[A-Z0-9.-]+\\.[A-Z]{2,}\\b");
    private static final Pattern PHONE =
            Pattern.compile("(?<!\\d)1[3-9]\\d{9}(?!\\d)");
    private static final Pattern ID_CARD =
            Pattern.compile("(?<!\\d)\\d{17}[0-9Xx](?!\\d)");
    private static final Pattern CONTROL =
            Pattern.compile("[\\p{Cntrl}&&[^\\r\\n\\t]]");

    private KnowledgeSanitizer() {
    }

    public static String sanitize(String text) {
        if (text == null) {
            return "";
        }
        String value = CONTROL.matcher(text).replaceAll(" ");
        value = JWT.matcher(value).replaceAll("[已过滤]");
        value = BEARER.matcher(value).replaceAll("[已过滤]");
        value = JSON_CREDENTIAL.matcher(value).replaceAll("[已过滤凭据]");
        value = API_KEY.matcher(value).replaceAll("[已过滤]");
        value = WINDOWS_PATH.matcher(value).replaceAll("[已过滤路径]");
        value = UNC_PATH.matcher(value).replaceAll("[已过滤路径]");
        value = UNIX_PATH.matcher(value).replaceAll("[已过滤路径]");
        value = EMAIL.matcher(value).replaceAll("[已过滤]");
        value = ID_CARD.matcher(value).replaceAll("[已过滤]");
        value = PHONE.matcher(value).replaceAll("[已过滤]");
        return value.trim();
    }

    /** Outbound payloads additionally cap length so one chunk cannot dominate the prompt. */
    public static String forModel(String text, int maxChars) {
        String sanitized = sanitize(text);
        if (sanitized.length() <= maxChars) {
            return sanitized;
        }
        return sanitized.substring(0, maxChars);
    }
}
