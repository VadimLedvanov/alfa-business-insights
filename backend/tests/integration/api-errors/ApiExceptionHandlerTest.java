package alpha.business.insight.core.controller;

import alpha.business.insight.core.qa.connectors.MockMvcEvidenceResultHandler;
import alpha.business.insight.core.qa.connectors.MockMvcRestAssured;
import io.qameta.allure.Step;
import io.restassured.module.mockmvc.specification.MockMvcRequestSpecification;
import org.junit.jupiter.api.BeforeEach;
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

import java.util.Map;
import java.util.UUID;

import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Проверяет единый формат ошибок HTTP API. */
@org.junit.jupiter.api.Tag("integration")
@org.junit.jupiter.api.Tag("api-errors")
class ApiExceptionHandlerTest {
    private MockMvc mvc;

    /** Собирает изолированный MVC-контекст и включает запись HTTP evidence в Allure. */
    @BeforeEach
    void setup() {
        mvc = MockMvcBuilders.standaloneSetup(new Errors())
                .setControllerAdvice(new ApiExceptionHandler())
                .alwaysDo(new MockMvcEvidenceResultHandler())
                .build();
    }
    /** Сохраняет статус и русское сообщение ошибки. */

    @Test
    @Step("Сохраняет статус и русское сообщение ошибки")
    void businessErrorPreservesStatusAndRussianMessage() throws Exception {
        request().when().get("/api/errors/missing").then().assertThat(status().isNotFound())
                .assertThat(jsonPath("$.code").value("NOT_FOUND"))
                .assertThat(jsonPath("$.message").value("Событие не найдено"))
                .assertThat(jsonPath("$.retryable").value(false))
                .assertThat(jsonPath("$.detail").doesNotExist());
    }
    /** Не раскрывает внутренние сведения об ошибке. */

    @Test
    @Step("Не раскрывает внутренние сведения об ошибке")
    void unexpectedErrorDoesNotExposeDetails() throws Exception {
        request().when().get("/api/errors/unexpected").then().assertThat(status().isInternalServerError())
                .assertThat(jsonPath("$.code").value("INTERNAL_ERROR"))
                .assertThat(jsonPath("$.message").value("Внутренняя ошибка сервера"))
                .assertThat(jsonPath("$.retryable").value(false));
    }
    /** Форматирует ошибку слишком большого файла. */

    @Test
    @Step("Форматирует ошибку слишком большого файла")
    void oversizedMultipartUsesSameFormat() throws Exception {
        request().when().get("/api/errors/upload").then().assertThat(status().isPayloadTooLarge())
                .assertThat(jsonPath("$.code").value("PAYLOAD_TOO_LARGE"))
                .assertThat(jsonPath("$.message").value("Размер PDF превышает 20 МБ"))
                .assertThat(jsonPath("$.retryable").value(false));
    }
    /** Возвращает 400 для ошибочного JSON и UUID. */

    @Test
    @Step("Возвращает 400 для ошибочного JSON и UUID")
    void malformedJsonAndInvalidUuidUseBadRequest() throws Exception {
        request().contentType("application/json").body("{").when().post("/api/errors/body").then()
                .assertThat(status().isBadRequest()).assertThat(jsonPath("$.code").value("BAD_REQUEST"))
                .assertThat(jsonPath("$.retryable").value(false));
        request().when().get("/api/errors/id/not-a-uuid").then().assertThat(status().isBadRequest())
                .assertThat(jsonPath("$.code").value("BAD_REQUEST"));
    }
    /** Сохраняет заголовок Allow для неподдерживаемого метода. */

    @Test
    @Step("Сохраняет заголовок Allow для неподдерживаемого метода")
    void unsupportedMethodPreservesAllowHeader() throws Exception {
        request().when().delete("/api/errors/body").then().assertThat(status().isMethodNotAllowed())
                .assertThat(header().string("Allow", "POST"))
                .assertThat(jsonPath("$.code").value("METHOD_NOT_ALLOWED"));
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
        void missing() { throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Событие не найдено"); }
        /** Возвращает неожиданную внутреннюю ошибку. */
        @GetMapping("/errors/unexpected")
        void unexpected() { throw new IllegalStateException("secret"); }
        /** Возвращает ошибку превышения лимита загрузки. */
        @GetMapping("/errors/upload")
        void upload() { throw new MaxUploadSizeExceededException(20 * 1024 * 1024); }
        /** Принимает тело для проверки ошибок разбора JSON и метода. */
        @PostMapping("/errors/body")
        void body(@RequestBody Map<String, Object> body) {}
        /** Принимает UUID, чтобы проверить ошибку преобразования пути. */
        @GetMapping("/errors/id/{id}")
        void id(@PathVariable UUID id) {}
    }
}
