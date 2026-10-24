package alpha.business.insight.core.dto;

import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;

/** Единый публичный контракт ошибки API. */
public record ApiError(String code, String message, boolean retryable) {
    public static ApiError of(HttpStatusCode status, String message) {
        HttpStatus known = HttpStatus.resolve(status.value());
        String code = switch (status.value()) {
            case 500 -> "INTERNAL_ERROR";
            case 413 -> "PAYLOAD_TOO_LARGE";
            default -> known == null ? "HTTP_" + status.value() : known.name();
        };
        String fallback = switch (status.value()) {
            case 400 -> "Некорректный запрос";
            case 401 -> "Требуется авторизация";
            case 403 -> "Недостаточно прав";
            case 404 -> "Ресурс не найден";
            case 405 -> "Метод запроса не поддерживается";
            case 409 -> "Конфликт данных";
            case 413 -> "Размер PDF превышает 50 МБ";
            case 415 -> "Неподдерживаемый формат данных";
            case 502 -> "Некорректный ответ внешнего компонента";
            case 503 -> "Сервис временно недоступен. Повторите попытку позже";
            default -> status.is4xxClientError() ? "Ошибка запроса" : "Внутренняя ошибка сервера";
        };
        return new ApiError(code, status.value() == 500 || message == null || message.isBlank() ? fallback : message,
                status.value() == 503);
    }
}
