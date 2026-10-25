package alpha.business.insight.core.qa.connectors;

import static org.assertj.core.api.Assertions.assertThat;

import alpha.business.insight.core.qa.helpers.TestEvidence;
import com.sun.net.httpserver.HttpServer;
import io.qameta.allure.Allure;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import io.qameta.allure.Step;
import io.qameta.allure.Story;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Проверяет удаление секретов и сохранение безопасного HTTP-контекста для отчёта. */
@Epic("Backend")
@Feature("HTTP evidence")
@Story("Безопасное сокрытие секретов в HTTP evidence")
@Tag("unit")
@Tag("diagnostics")
class HttpEvidenceTest {
    /** Проверяет редактирование JSON-токенов с сохранением статуса и адреса запроса. */
    @Test
    @DisplayName("TestUnitRedactJsonSecretsAndPreserveContext")
    void TestUnitRedactJsonSecretsAndPreserveContext() {
        // Подготавливает HTTP-снимок с секретами в JSON.
        String evidence =
                "status=200 url=https://backend.test/auth/me content-type=application/json "
                        + "body={\"username\":\"director\",\"access_token\":\"access-7dfe\","
                        + "\"refresh_token\":\"refresh-90ab\",\"id_token\":\"identity-c341\","
                        + "\"password\":\"password-f084\",\"client_secret\":\"client-112a\"}";

        TestEvidence.step(
                "Скрыть секретные JSON-поля и сохранить контекст запроса",
                List.of(
                        new TestEvidence.Input("Входное HTTP evidence", evidence),
                        new TestEvidence.Input(
                                "Окружение",
                                "unit: HttpEvidence.redact(String); no network request")),
                () -> HttpEvidence.redact(evidence),
                redacted -> {
                    // Проверяет, что секреты удалены, а полезный контекст сохранён.
                    assertNoSecrets(
                            redacted,
                            "access-7dfe",
                            "refresh-90ab",
                            "identity-c341",
                            "password-f084",
                            "client-112a");

                    assertThat(redacted)
                            .contains(
                                    "status=200",
                                    "https://backend.test/auth/me",
                                    "application/json",
                                    "director");
                });
    }

    /** Проверяет редактирование URL-кодированного пароля в данных формы. */
    @Test
    @DisplayName("TestUnitRedactUrlEncodedPassword")
    void TestUnitRedactUrlEncodedPassword() {
        // Подготавливает снимок формы с URL-кодированным паролем.
        String evidence =
                "status=400 url=https://backend.test/login content-type=application/x-www-form-urlencoded "
                        + "body=username=director&password=secret%2Bvalue%40123&grant_type=password";

        TestEvidence.step(
                "Скрыть пароль в form-urlencoded evidence",
                List.of(
                        new TestEvidence.Input("Входное HTTP evidence", evidence),
                        new TestEvidence.Input(
                                "Окружение",
                                "unit: HttpEvidence.redact(String); no network request")),
                () -> HttpEvidence.redact(evidence),
                redacted -> {
                    // Проверяет отсутствие пароля и сохранение безопасных полей.
                    assertNoSecrets(redacted, "secret%2Bvalue%40123", "secret+value@123");

                    assertThat(redacted)
                            .contains(
                                    "director",
                                    "grant_type=password",
                                    "status=400",
                                    "application/x-www-form-urlencoded");
                });
    }

    /** Проверяет удаление секретов из заголовков Authorization. */
    @Test
    @DisplayName("TestUnitRedactAuthorizationCredentials")
    void TestUnitRedactAuthorizationCredentials() {
        // Подготавливает заголовки с Bearer- и Basic-учётными данными.
        String evidence =
                "Authorization: Bearer bearer-secret-45c1\nAuthorization: Basic basic-secret-55d9";

        TestEvidence.step(
                "Скрыть bearer- и basic-учётные данные",
                List.of(
                        new TestEvidence.Input("Заголовки HTTP", evidence),
                        new TestEvidence.Input(
                                "Окружение",
                                "unit: HttpEvidence.redact(String); no network request")),
                () -> HttpEvidence.redact(evidence),
                redacted -> {
                    // Проверяет удаление учётных данных.
                    assertNoSecrets(redacted, "bearer-secret-45c1", "basic-secret-55d9");
                });
    }

    /** Проверяет удаление значений cookie-заголовков из HTTP-снимка. */
    @Test
    @DisplayName("TestUnitRedactCookieHeaders")
    void TestUnitRedactCookieHeaders() {
        // Подготавливает заголовки Cookie и Set-Cookie с тестовыми значениями.
        String evidence =
                "Cookie: session=session-cookie-17fa; theme=dark\n"
                        + "Set-Cookie: refresh=refresh-cookie-1bc2; HttpOnly; Secure";

        TestEvidence.step(
                "Скрыть значения Cookie и Set-Cookie",
                List.of(
                        new TestEvidence.Input("Заголовки HTTP", evidence),
                        new TestEvidence.Input(
                                "Окружение",
                                "unit: HttpEvidence.redact(String); no network request")),
                () -> HttpEvidence.redact(evidence),
                redacted -> {
                    // Проверяет удаление значений cookie.
                    assertNoSecrets(redacted, "session-cookie-17fa", "refresh-cookie-1bc2");
                });
    }

    /** Скрывает все части multipart-секрета и сохраняет безопасные поля. */
    @Test
    @DisplayName("TestUnitRedactMultipartSecrets")
    void TestUnitRedactMultipartSecrets() {
        // Готовит multipart-поля с многострочными секретами и безопасным названием файла.
        String[] passwordValues = {"first, \"quoted-secret-1\"", "second\nline-secret-2"};
        String[] titleValues = {"quarterly report.pdf", "draft copy"};
        String[] descriptionValues = {"upload", "password=embedded-secret-3"};

        TestEvidence.step(
                "Скрыть все значения секретных multipart-полей",
                List.of(
                        new TestEvidence.Input(
                                "Поля multipart",
                                Map.of(
                                        "PASSWORD", List.of(passwordValues),
                                        "title", List.of(titleValues),
                                        "description", List.of(descriptionValues))),
                        new TestEvidence.Input(
                                "Окружение",
                                "unit: HttpEvidence.formField(String, String[]); no network request")),
                () ->
                        Map.of(
                                "PASSWORD", HttpEvidence.formField("PASSWORD", passwordValues),
                                "title", HttpEvidence.formField("title", titleValues),
                                "description",
                                        HttpEvidence.formField("description", descriptionValues)),
                redacted -> {
                    // Проверяет полное сокрытие секрета и сохранение безопасных значений.
                    assertThat(redacted)
                            .containsEntry("PASSWORD", "PASSWORD=[REDACTED]")
                            .containsEntry("title", "title=quarterly report.pdf, draft copy")
                            .containsEntry(
                                    "description", "description=upload, password=[REDACTED]");
                    assertNoSecrets(
                            redacted.toString(),
                            "quoted-secret-1",
                            "line-secret-2",
                            "embedded-secret-3");

                    String summary =
                            String.join(
                                    "\n",
                                    HttpEvidence.formField("PASSWORD", passwordValues),
                                    HttpEvidence.formField("description", descriptionValues));
                    assertThat(summary)
                            .contains(
                                    "PASSWORD=[REDACTED]",
                                    "description=upload, password=[REDACTED]");
                    assertNoSecrets(
                            summary, "quoted-secret-1", "line-secret-2", "embedded-secret-3");
                });
    }

    /** Проверяет сохранение безопасных полей HTTP-запроса и ответа. */
    @Test
    @DisplayName("TestUnitPreserveSafeHttpEvidence")
    void TestUnitPreserveSafeHttpEvidence() {
        // Подготавливает HTTP-снимок только с безопасными данными.
        String evidence =
                "status=201 url=https://backend.test/documents/42 username=director "
                        + "content-type=application/pdf filename=report.pdf";

        TestEvidence.step(
                "Сохранить безопасные HTTP-поля без изменения",
                List.of(
                        new TestEvidence.Input("Входное HTTP evidence", evidence),
                        new TestEvidence.Input(
                                "Окружение",
                                "unit: HttpEvidence.redact(String); no network request")),
                () -> HttpEvidence.redact(evidence),
                redacted -> {
                    // Проверяет, что безопасные данные остались в снимке.
                    assertThat(redacted)
                            .contains(
                                    "status=201",
                                    "https://backend.test/documents/42",
                                    "director",
                                    "application/pdf",
                                    "report.pdf");
                });
    }

    /** Проверяет передачу повторяющихся и закодированных query-параметров по локальному HTTP. */
    @Test
    @DisplayName("TestUnitPreserveEncodedAndRepeatedQueryParameters")
    @Story("Передача query-параметров в локальный HTTP запрос")
    @Step("Проверяет сохранение query-параметров HTTP-клиентом")
    void TestUnitPreserveEncodedAndRepeatedQueryParameters() throws Exception {
        AtomicReference<String> requestPath = new AtomicReference<>();
        AtomicReference<String> rawQuery = new AtomicReference<>();
        HttpServer server =
                Allure.step(
                        "1. Запустить локальный HTTP-приёмник query",
                        () -> {
                            HttpServer localServer =
                                    HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
                            localServer.createContext(
                                    "/",
                                    exchange -> {
                                        requestPath.set(exchange.getRequestURI().getPath());
                                        rawQuery.set(exchange.getRequestURI().getRawQuery());
                                        byte[] response = "{}".getBytes(StandardCharsets.UTF_8);
                                        exchange.getResponseHeaders()
                                                .add("Content-Type", "application/json");
                                        exchange.sendResponseHeaders(200, response.length);
                                        try (OutputStream output = exchange.getResponseBody()) {
                                            output.write(response);
                                        }
                                    });
                            localServer.start();
                            return localServer;
                        });
        try {
            BackendClient client =
                    new BackendClient(
                            URI.create(
                                    "http://127.0.0.1:" + server.getAddress().getPort() + "/api"),
                            ignored -> {});
            var response =
                    Allure.step(
                            "2. Отправить GET с encoded и повторными query",
                            () ->
                                    client.get(
                                            "/events?title=%5BSYNTHETIC+TEST%5D+rate+%26+cash"
                                                    + "&sourceType=URL&sourceType=TEXT"
                                                    + "&needle=rate%2B5%25",
                                            null));

            Allure.step(
                    "3. Проверить фактический путь и значения запроса",
                    () -> {
                        assertThat(response.statusCode()).isEqualTo(200);
                        assertThat(requestPath.get()).isEqualTo("/api/events");
                        assertThat(rawQuery.get()).isNotBlank();
                        Map<String, List<String>> parameters = decodeQuery(rawQuery.get());
                        assertThat(parameters)
                                .containsEntry("title", List.of("[SYNTHETIC TEST] rate & cash"))
                                .containsEntry("sourceType", List.of("URL", "TEXT"))
                                .containsEntry("needle", List.of("rate+5%"));
                    });
        } finally {
            server.stop(0);
        }
    }

    /** Декодирует query снимок локального сервера с сохранением порядка повторных ключей. */
    private static Map<String, List<String>> decodeQuery(String rawQuery) {
        Map<String, List<String>> decoded = new LinkedHashMap<>();
        for (String pair : rawQuery.split("&", -1)) {
            String[] parts = pair.split("=", 2);
            String name = URLDecoder.decode(parts[0], StandardCharsets.UTF_8);
            String value =
                    parts.length == 1 ? "" : URLDecoder.decode(parts[1], StandardCharsets.UTF_8);
            decoded.computeIfAbsent(name, ignored -> new ArrayList<>()).add(value);
        }
        return decoded;
    }

    /** Проверяет отсутствие заданных секретов в отредактированной строке. */
    private static void assertNoSecrets(String redacted, String... secrets) {
        for (String secret : secrets) {
            assertThat(redacted).doesNotContain(secret);
        }
    }
}
