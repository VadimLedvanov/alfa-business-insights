package alpha.business.insight.core.qa.connectors;

import static io.restassured.config.RedirectConfig.redirectConfig;

import io.restassured.RestAssured;
import io.restassured.config.HttpClientConfig;
import io.restassured.config.RestAssuredConfig;
import io.restassured.specification.RequestSpecification;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.apache.http.conn.params.ConnManagerPNames;
import org.apache.http.impl.client.DefaultHttpClient;
import org.apache.http.impl.client.DefaultHttpRequestRetryHandler;
import org.apache.http.params.CoreConnectionPNames;

/** Создаёт локальные HTTP-спецификации с конечными таймаутами и без повторных запросов. */
final class RestAssuredRequests {
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(30);

    /** Запрещает создавать экземпляры служебного создателя HTTP-спецификаций. */
    private RestAssuredRequests() {}

    /** Создаёт отдельный Rest Assured запрос к URI с redirect и retry отключёнными. */
    static RequestSpecification forUri(URI uri) {
        HttpClientConfig httpClient =
                HttpClientConfig.httpClientConfig()
                        .dontReuseHttpClientInstance()
                        .httpClientFactory(
                                () -> {
                                    DefaultHttpClient client = new DefaultHttpClient();
                                    client.setHttpRequestRetryHandler(
                                            new DefaultHttpRequestRetryHandler(0, false));
                                    client.getParams()
                                            .setIntParameter(
                                                    CoreConnectionPNames.CONNECTION_TIMEOUT,
                                                    Math.toIntExact(CONNECT_TIMEOUT.toMillis()));
                                    client.getParams()
                                            .setIntParameter(
                                                    CoreConnectionPNames.SO_TIMEOUT,
                                                    Math.toIntExact(READ_TIMEOUT.toMillis()));
                                    client.getParams()
                                            .setLongParameter(
                                                    ConnManagerPNames.TIMEOUT,
                                                    CONNECT_TIMEOUT.toMillis());
                                    return client;
                                });
        RestAssuredConfig requestConfig =
                RestAssuredConfig.config()
                        .redirect(redirectConfig().followRedirects(false))
                        .httpClient(httpClient);
        RequestSpecification request =
                RestAssured.given()
                        .config(requestConfig)
                        .baseUri(uri.getScheme() + "://" + uri.getRawAuthority())
                        .basePath(uri.getPath());
        appendQueryParameters(request, uri.getRawQuery());
        return request;
    }

    /** Декодирует пары query один раз и сохраняет повторяющиеся имена параметров. */
    private static void appendQueryParameters(RequestSpecification request, String rawQuery) {
        if (rawQuery == null || rawQuery.isEmpty()) {
            return;
        }
        for (String pair : rawQuery.split("&", -1)) {
            String[] parts = pair.split("=", 2);
            String name = URLDecoder.decode(parts[0], StandardCharsets.UTF_8);
            String value =
                    parts.length == 1 ? "" : URLDecoder.decode(parts[1], StandardCharsets.UTF_8);
            request.queryParam(name, value);
        }
    }
}
