package alpha.business.insight.core.controller;

import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasKey;
import static org.hamcrest.Matchers.not;

import alpha.business.insight.core.qa.connectors.MockMvcEvidenceResultHandler;
import alpha.business.insight.core.qa.connectors.MockMvcRestAssured;
import alpha.business.insight.core.qa.helpers.SizedPdfFixtures;
import io.qameta.allure.Step;
import io.restassured.module.mockmvc.specification.MockMvcRequestSpecification;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.server.ResponseStatusException;

/** Проверяет единый формат ошибок HTTP API. */
@Tag("integration")
@Tag("api-errors")
class ApiExceptionHandlerTest {
    private MockMvc mvc;

    /** Собирает изолированный MVC-контекст и включает запись HTTP evidence в Allure. */
    @BeforeEach
    void setup() {
        mvc =
                MockMvcBuilders.standaloneSetup(new Errors())
                        .setControllerAdvice(new ApiExceptionHandler())
                        .alwaysDo(new MockMvcEvidenceResultHandler())
                        .build();
    }

    /** Сохраняет статус и русское сообщение ошибки. */
    @Test
    @Step("Сохраняет статус и русское сообщение ошибки")
    void businessErrorPreservesStatusAndRussianMessage() throws Exception {
        request()
                .when()
                .get("/api/errors/missing")
                .then()
                .statusCode(404)
                .body("code", equalTo("NOT_FOUND"))
                .body("message", equalTo("Событие не найдено"))
                .body("retryable", equalTo(false))
                .body("", not(hasKey("detail")));
    }

    /** Не раскрывает внутренние сведения об ошибке. */
    @Test
    @Step("Не раскрывает внутренние сведения об ошибке")
    void unexpectedErrorDoesNotExposeDetails() throws Exception {
        request()
                .when()
                .get("/api/errors/unexpected")
                .then()
                .statusCode(500)
                .body("code", equalTo("INTERNAL_ERROR"))
                .body("message", equalTo("Внутренняя ошибка сервера"))
                .body("retryable", equalTo(false));
    }

    /** Форматирует ошибку слишком большого файла. */
    @Test
    @Step("Форматирует ошибку слишком большого файла")
    void oversizedMultipartUsesSameFormat() throws Exception {
        request()
                .when()
                .get("/api/errors/upload")
                .then()
                .statusCode(413)
                .body("code", equalTo("PAYLOAD_TOO_LARGE"))
                .body("message", equalTo("Размер PDF превышает 50 МБ"))
                .body("retryable", equalTo(false));
    }

    /** Возвращает 400 для ошибочного JSON и UUID. */
    @Test
    @Step("Возвращает 400 для ошибочного JSON и UUID")
    void malformedJsonAndInvalidUuidUseBadRequest() throws Exception {
        request()
                .contentType("application/json")
                .body("{")
                .when()
                .post("/api/errors/body")
                .then()
                .statusCode(400)
                .body("code", equalTo("BAD_REQUEST"))
                .body("retryable", equalTo(false));
        request()
                .when()
                .get("/api/errors/id/not-a-uuid")
                .then()
                .statusCode(400)
                .body("code", equalTo("BAD_REQUEST"));
    }

    /** Сохраняет заголовок Allow для неподдерживаемого метода. */
    @Test
    @Step("Сохраняет заголовок Allow для неподдерживаемого метода")
    void unsupportedMethodPreservesAllowHeader() throws Exception {
        request()
                .when()
                .delete("/api/errors/body")
                .then()
                .statusCode(405)
                .header("Allow", equalTo("POST"))
                .body("code", equalTo("METHOD_NOT_ALLOWED"));
    }

    /** Создаёт новую request-local спецификацию для вызова API. */
    private MockMvcRequestSpecification request() {
        return MockMvcRestAssured.request(mvc);
    }

    /** Предоставляет endpoints, которые выбрасывают ошибки для проверки advice. */
    @RestController
    static class Errors {

        /** Возвращает бизнес-ошибку NOT_FOUND. */
        @GetMapping("/errors/missing")
        void missing() {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Событие не найдено");
        }

        /** Возвращает неожиданную внутреннюю ошибку. */
        @GetMapping("/errors/unexpected")
        void unexpected() {
            throw new IllegalStateException("secret");
        }

        /** Возвращает ошибку превышения лимита загрузки. */
        @GetMapping("/errors/upload")
        void upload() {
            throw new MaxUploadSizeExceededException(SizedPdfFixtures.MAX_UPLOAD_BYTES + 1);
        }

        /** Принимает тело для проверки ошибок разбора JSON и метода. */
        @PostMapping("/errors/body")
        void body(@RequestBody Map<String, Object> body) {}

        /** Принимает UUID, чтобы проверить ошибку преобразования пути. */
        @GetMapping("/errors/id/{id}")
        void id(@PathVariable UUID id) {}
    }
}
