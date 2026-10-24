package alpha.business.insight.core.qa.connectors;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.qameta.allure.Allure;
import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/** Получает токены тестового realm и сохраняет ответы с редактированием секретов. */
public final class KeycloakClient {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String REALM = "alfa-business-insights";
    private final URI baseUri;
    private final Consumer<String> exchangeRecorder;

    /** Создаёт клиент Keycloak с адресом realm и записью HTTP-обменов. */
    public KeycloakClient(URI baseUri, Consumer<String> exchangeRecorder) {
        this.baseUri = baseUri;
        this.exchangeRecorder = exchangeRecorder;
    }

    /** Получает реальный access token пользователя по паролю и проверяет ответ token endpoint. */
    public String accessToken(String username, String password) throws Exception {
        URI uri = baseUri.resolve("/realms/" + REALM + "/protocol/openid-connect/token");
        return Allure.step(
                "POST " + uri.getPath() + " — получить токен тестового пользователя",
                () -> {
                    String form =
                            Map.of(
                                            "grant_type",
                                            "password",
                                            "client_id",
                                            "alfa-backend",
                                            "username",
                                            username,
                                            "password",
                                            password)
                                    .entrySet()
                                    .stream()
                                    .map(
                                            entry ->
                                                    encode(entry.getKey())
                                                            + "="
                                                            + encode(entry.getValue()))
                                    .collect(Collectors.joining("&"));
                    Map<String, String> headers =
                            Map.of("Content-Type", "application/x-www-form-urlencoded");
                    String requestEvidence = attachRequest(uri, headers, form);
                    Response response = request(uri).headers(headers).body(form).when().post();
                    String responseBody = response.asString();
                    String responseEvidence =
                            requestEvidence
                                    + "\n\nResponse:\nHTTP "
                                    + response.statusCode()
                                    + "\nHeaders:\n"
                                    + HttpEvidence.headers(responseHeaders(response))
                                    + "\nBody:\n"
                                    + HttpEvidence.redact(responseBody);
                    Allure.addAttachment("HTTP response", "text/plain", responseEvidence);
                    Allure.addAttachment(
                            "Keycloak response body (redacted)",
                            "application/json",
                            HttpEvidence.redact(responseBody));
                    exchangeRecorder.accept("RESPONSE\n" + responseEvidence);

                    if (response.statusCode() != 200) {
                        throw new AssertionError(
                                "Keycloak token endpoint returned HTTP " + response.statusCode());
                    }
                    JsonNode json = JSON.readTree(responseBody);
                    String accessToken = json.path("access_token").asText();
                    if (accessToken.isBlank())
                        throw new AssertionError("Keycloak returned an empty access token");
                    return accessToken;
                });
    }

    /** Создаёт свежую спецификацию с таймаутами, без redirect и повторов. */
    private RequestSpecification request(URI uri) {
        return RestAssuredRequests.forUri(uri);
    }

    /** Сохраняет заданные поля запроса; тело формы очищается от пароля до записи. */
    private String attachRequest(URI uri, Map<String, String> headers, String body) {
        String safeBody = HttpEvidence.redact(body);
        String requestEvidence =
                "Request specification (supplied values; not a wire capture): POST "
                        + uri
                        + "\nHeaders:\n"
                        + HttpEvidence.redact(
                                "Content-Type: " + headers.getOrDefault("Content-Type", ""))
                        + "\nBody:\n"
                        + safeBody;
        exchangeRecorder.accept("REQUEST\n" + requestEvidence);
        Allure.addAttachment("HTTP request POST", "text/plain", requestEvidence);
        Allure.addAttachment("HTTP request form body (redacted)", "text/plain", safeBody);
        return requestEvidence;
    }

    /** Преобразует заголовки ответа для общей безопасной форматирующей функции. */
    private static Map<String, List<String>> responseHeaders(Response response) {
        Map<String, List<String>> headers = new LinkedHashMap<>();
        response.getHeaders()
                .asList()
                .forEach(
                        header ->
                                headers.computeIfAbsent(
                                                header.getName(),
                                                ignored -> new java.util.ArrayList<>())
                                        .add(header.getValue()));
        return headers;
    }

    /** Кодирует одно значение application/x-www-form-urlencoded в UTF-8. */
    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
