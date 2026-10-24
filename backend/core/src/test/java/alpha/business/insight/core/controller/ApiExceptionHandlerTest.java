package alpha.business.insight.core.controller;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class ApiExceptionHandlerTest {
    MockMvc mvc;

    @BeforeEach
    void setup() {
        mvc = MockMvcBuilders.standaloneSetup(new Errors())
                .setControllerAdvice(new ApiExceptionHandler()).build();
    }

    @Test
    void businessErrorPreservesStatusAndRussianMessage() throws Exception {
        mvc.perform(get("/errors/missing")).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"))
                .andExpect(jsonPath("$.message").value("Событие не найдено"))
                .andExpect(jsonPath("$.retryable").value(false))
                .andExpect(jsonPath("$.detail").doesNotExist());
    }

    @Test
    void unexpectedErrorDoesNotExposeDetails() throws Exception {
        mvc.perform(get("/errors/unexpected")).andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
                .andExpect(jsonPath("$.message").value("Внутренняя ошибка сервера"))
                .andExpect(jsonPath("$.retryable").value(false));
    }

    @Test
    void oversizedMultipartUsesSameFormat() throws Exception {
        mvc.perform(get("/errors/upload")).andExpect(status().isPayloadTooLarge())
                .andExpect(jsonPath("$.code").value("PAYLOAD_TOO_LARGE"))
                .andExpect(jsonPath("$.message").value("Размер PDF превышает 20 МБ"))
                .andExpect(jsonPath("$.retryable").value(false));
    }

    @Test
    void malformedJsonAndInvalidUuidUseBadRequest() throws Exception {
        mvc.perform(post("/errors/body").contentType("application/json").content("{"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("BAD_REQUEST"))
                .andExpect(jsonPath("$.retryable").value(false));
        mvc.perform(get("/errors/id/not-a-uuid")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BAD_REQUEST"));
    }

    @Test
    void unsupportedMethodPreservesAllowHeader() throws Exception {
        mvc.perform(delete("/errors/body")).andExpect(status().isMethodNotAllowed())
                .andExpect(header().string("Allow", "POST"))
                .andExpect(jsonPath("$.code").value("METHOD_NOT_ALLOWED"));
    }

    @RestController
    static class Errors {
        @GetMapping("/errors/missing")
        void missing() { throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Событие не найдено"); }
        @GetMapping("/errors/unexpected")
        void unexpected() { throw new IllegalStateException("secret"); }
        @GetMapping("/errors/upload")
        void upload() { throw new MaxUploadSizeExceededException(20 * 1024 * 1024); }
        @PostMapping("/errors/body")
        void body(@RequestBody java.util.Map<String, Object> body) {}
        @GetMapping("/errors/id/{id}")
        void id(@PathVariable UUID id) {}
    }
}
