package alpha.business.insight.core.qa.connectors;

import alpha.business.insight.core.qa.helpers.BinaryEvidence;
import feign.Capability;
import feign.Client;
import feign.Request;
import feign.Response;
import io.qameta.allure.Allure;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/** Тестовый Feign-декоратор, сохраняющий фактические запросы и ответы. */
public final class AllureFeignCapability implements Capability {
    /** Создаёт декоратор Feign-клиента для Allure evidence. */
    public AllureFeignCapability() {}

    /** Оборачивает production Feign-клиент декоратором записи фактического обмена. */
    @Override
    public Client enrich(Client delegate) {
        return (request, options) -> exchange(delegate, request, options);
    }

    /** Выполняет Feign-запрос и сохраняет фактические данные обмена. */
    private static Response exchange(Client delegate, Request request, Request.Options options)
            throws IOException {
        if (Allure.getLifecycle().getCurrentTestCase().isEmpty()) {
            // HTTP evidence снимает BackendClient в тестовом потоке; серверный Feign без контекста
            // не прикрепляем.
            return delegate.execute(request, options);
        }
        String path = path(request.url());
        return Allure.step(
                request.method() + " " + HttpEvidence.redact(path) + " — Feign exchange",
                () -> {
                    attachRequest(request);
                    try {
                        Response response = delegate.execute(request, options);
                        return attachResponse(response);
                    } catch (IOException failure) {
                        Allure.addAttachment(
                                "Feign transport error",
                                "text/plain",
                                HttpEvidence.redact(
                                        failure.getClass().getName()
                                                + ": "
                                                + failure.getMessage()));
                        throw failure;
                    }
                });
    }

    /** Сохраняет переданные Feign-клиенту заголовки и тело запроса. */
    private static void attachRequest(Request request) throws Exception {
        byte[] body = request.body();
        String contentType = firstHeader(request.headers(), "Content-Type");
        StringBuilder summary =
                new StringBuilder(request.method())
                        .append(' ')
                        .append(request.url())
                        .append("\nHeaders:\n")
                        .append(HttpEvidence.headers(toListHeaders(request.headers())))
                        .append("\nBody:\n")
                        .append(bodySummary(body, contentType));
        Allure.addAttachment(
                "Feign request", "text/plain", HttpEvidence.redact(summary.toString()));
        if (body != null && body.length > 0) attachBody("Feign request body", contentType, body);
    }

    /** Сохраняет статус и тело ответа, затем возвращает читаемый ответ Feign. */
    private static Response attachResponse(Response response) throws Exception {
        byte[] body = response.body() == null ? new byte[0] : readBody(response);
        String contentType = firstHeader(response.headers(), "Content-Type");
        StringBuilder summary = new StringBuilder("HTTP ").append(response.status());
        if (response.reason() != null) summary.append(' ').append(response.reason());
        summary.append("\nHeaders:\n")
                .append(HttpEvidence.headers(toListHeaders(response.headers())))
                .append("\nBody:\n")
                .append(bodySummary(body, contentType));
        Allure.addAttachment(
                "Feign response", "text/plain", HttpEvidence.redact(summary.toString()));
        if (body.length > 0) attachBody("Feign response body", contentType, body);
        return response.body() == null ? response : response.toBuilder().body(body).build();
    }

    /** Читает исходный поток ответа перед прикреплением и восстановлением тела. */
    private static byte[] readBody(Response response) throws IOException {
        try (var input = response.body().asInputStream()) {
            return input.readAllBytes();
        }
    }

    /** Формирует краткое описание тела и его фактического вложения. */
    private static String bodySummary(byte[] body, String contentType) throws Exception {
        if (body == null || body.length == 0) return "(empty)\n";
        if (isText(contentType)) return body.length + " bytes; see text body attachment\n";
        return body.length + " bytes; SHA-256=" + sha256(body) + "; see binary body attachment\n";
    }

    /** Прикрепляет текстовое или бинарное тело с редактированием секретов. */
    private static void attachBody(String name, String contentType, byte[] body) throws Exception {
        String type =
                contentType == null || contentType.isBlank()
                        ? "application/octet-stream"
                        : contentType;
        if (isText(type)) {
            Charset charset = StandardCharsets.UTF_8;
            try {
                var parsed = org.springframework.http.MediaType.parseMediaType(type).getCharset();
                if (parsed != null) charset = parsed;
            } catch (IllegalArgumentException ignored) {
                // Использовать UTF-8 для неверной или отсутствующей кодировки.
            }
            String safeBody = HttpEvidence.redact(new String(body, charset));
            Allure.addAttachment(
                    name,
                    type.toLowerCase(Locale.ROOT).contains("json")
                            ? "application/json"
                            : "text/plain",
                    safeBody);
            return;
        }
        String extension = type.toLowerCase(Locale.ROOT).contains("pdf") ? ".pdf" : ".bin";
        BinaryEvidence.attach(name, type, body, extension);
    }

    /** Определяет, можно ли безопасно сохранить тело как текст. */
    private static boolean isText(String contentType) {
        if (contentType == null) return false;
        String type = contentType.toLowerCase(Locale.ROOT);
        return type.startsWith("text/")
                || type.contains("json")
                || type.contains("xml")
                || type.contains("yaml")
                || type.contains("x-www-form-urlencoded");
    }

    /** Ищет первый заголовок без учёта регистра имени. */
    private static String firstHeader(Map<String, Collection<String>> headers, String name) {
        return headers.entrySet().stream()
                .filter(entry -> entry.getKey().equalsIgnoreCase(name))
                .flatMap(entry -> entry.getValue().stream())
                .findFirst()
                .orElse("");
    }

    /** Преобразует заголовки Feign в список значений с нечувствительными к регистру ключами. */
    private static Map<String, List<String>> toListHeaders(
            Map<String, Collection<String>> headers) {
        Map<String, List<String>> result = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        headers.forEach((name, values) -> result.put(name, new ArrayList<>(values)));
        return result;
    }

    /** Возвращает путь и query URL без схемы, имени узла и секрета пользователя. */
    private static String path(String url) {
        try {
            String path = java.net.URI.create(url).getRawPath();
            String query = java.net.URI.create(url).getRawQuery();
            return query == null ? path : path + "?" + query;
        } catch (IllegalArgumentException invalidUrl) {
            return url;
        }
    }

    /** Вычисляет SHA-256 бинарного тела для проверки его целостности. */
    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException impossible) {
            throw new AssertionError(impossible);
        }
    }
}
