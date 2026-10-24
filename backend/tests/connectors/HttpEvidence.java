package alpha.business.insight.core.qa.connectors;

import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Редактирует секреты и форматирует HTTP evidence для отчётов Allure. */
public final class HttpEvidence {
    private static final Pattern SECRET_FIELD =
            Pattern.compile(
                    "(?i)([\\\"']?(?:access_token|refresh_token|id_token|password|client_secret|authorization|cookie|set-cookie)[\\\"']?\\s*[:=]\\s*)(\\\"(?:\\\\.|[^\\\"\\\\])*\\\"|'(?:\\\\.|[^'\\\\])*'|[^&\\s,}]+)");
    private static final Pattern SENSITIVE_HEADER =
            Pattern.compile(
                    "(?im)^(\\s*(?:authorization|proxy-authorization|cookie|set-cookie)\\s*:\\s*).*$");
    private static final Pattern BEARER = Pattern.compile("(?i)(bearer\\s+)[A-Za-z0-9._~+/=-]+");

    /** Запрещает создавать экземпляры статического средства форматирования. */
    private HttpEvidence() {}

    /** Маскирует токены, пароли, cookie и bearer-значения в evidence. */
    public static String redact(String evidence) {
        if (evidence == null || evidence.isEmpty()) return evidence;
        String redacted = SENSITIVE_HEADER.matcher(evidence).replaceAll("$1[REDACTED]");
        Matcher secrets = SECRET_FIELD.matcher(redacted);
        StringBuffer safe = new StringBuffer();
        while (secrets.find()) {
            String value = secrets.group(2);
            String masked =
                    value.startsWith("\"") && value.endsWith("\"")
                            ? "\"[REDACTED]\""
                            : value.startsWith("'") && value.endsWith("'")
                                    ? "'[REDACTED]'"
                                    : "[REDACTED]";
            secrets.appendReplacement(safe, Matcher.quoteReplacement(secrets.group(1) + masked));
        }
        secrets.appendTail(safe);
        redacted = safe.toString();
        return BEARER.matcher(redacted).replaceAll("$1[REDACTED]");
    }

    /** Форматирует заголовки и скрывает значения чувствительных полей. */
    static String headers(Map<String, java.util.List<String>> headers) {
        StringBuilder result = new StringBuilder();
        headers.forEach(
                (name, values) -> {
                    String value =
                            isSensitiveHeader(name) ? "[REDACTED]" : String.join(", ", values);
                    result.append(name).append(": ").append(value).append('\n');
                });
        return redact(result.toString()).stripTrailing();
    }

    /** Форматирует поле формы и целиком скрывает значения чувствительных имён. */
    static String formField(String name, String[] values) {
        String fieldValues = values == null ? "" : String.join(", ", values);
        String safeValues = isSensitiveField(name) ? "[REDACTED]" : redact(fieldValues);
        return name + "=" + safeValues;
    }

    /** Проверяет, обозначает ли Content-Type PDF-документ. */
    static boolean isPdf(String contentType) {
        return contentType.toLowerCase(Locale.ROOT).contains("application/pdf");
    }

    /** Определяет, нужно ли полностью скрыть значение заголовка. */
    private static boolean isSensitiveHeader(String name) {
        return name.equalsIgnoreCase("authorization")
                || name.equalsIgnoreCase("proxy-authorization")
                || name.equalsIgnoreCase("cookie")
                || name.equalsIgnoreCase("set-cookie");
    }

    /** Проверяет имена полей формы, значения которых нельзя сохранять в evidence. */
    private static boolean isSensitiveField(String name) {
        return isSensitiveHeader(name)
                || name.equalsIgnoreCase("access_token")
                || name.equalsIgnoreCase("refresh_token")
                || name.equalsIgnoreCase("id_token")
                || name.equalsIgnoreCase("password")
                || name.equalsIgnoreCase("client_secret");
    }
}
