package alpha.business.insight.core.controller;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

/**
 * Преобразует отклонение слишком большого multipart-запроса в документированную ошибку API.
 */
@RestControllerAdvice
public class UploadExceptionHandler {
    /**
     * Возвращает HTTP 413, даже если multipart-парсер отклонил запрос до вызова контроллера.
     *
     * @param exception ошибка ограничения размера multipart-запроса
     * @return описание ошибки в формате ProblemDetail
     */
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ProblemDetail tooLarge(MaxUploadSizeExceededException exception) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.PAYLOAD_TOO_LARGE, "Размер PDF превышает 20 МБ");
    }
}
