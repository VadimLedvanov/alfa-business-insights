package alpha.business.insight.core.controller;

import org.springframework.http.HttpStatus;
import alpha.business.insight.core.dto.ApiError;
import alpha.business.insight.core.ml.ComponentUnavailableException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Преобразует ошибки контроллеров и Spring MVC в единый публичный контракт API.
 */
@RestControllerAdvice
public class ApiExceptionHandler extends ResponseEntityExceptionHandler {
    @Override
    protected ResponseEntity<Object> handleExceptionInternal(Exception exception, Object body, HttpHeaders headers,
                                                              HttpStatusCode status, WebRequest request) {
        String reason = exception instanceof ResponseStatusException response ? response.getReason() : null;
        ApiError error = exception instanceof ComponentUnavailableException component
                ? new ApiError(component.getCode(), component.getReason(), true) : ApiError.of(status, reason);
        return new ResponseEntity<>(error, headers, status);
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ApiError> forbidden(AccessDeniedException exception) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(ApiError.of(HttpStatus.FORBIDDEN, null));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> unexpected() {
        return ResponseEntity.internalServerError().body(ApiError.of(HttpStatus.INTERNAL_SERVER_ERROR, null));
    }
}
