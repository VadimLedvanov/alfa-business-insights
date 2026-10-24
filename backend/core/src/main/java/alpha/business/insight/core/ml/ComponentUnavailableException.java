package alpha.business.insight.core.ml;

import lombok.Getter;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/** Отказ внешнего компонента, после восстановления которого запрос можно повторить. */
@Getter
public class ComponentUnavailableException extends ResponseStatusException {
    private final String code;

    public ComponentUnavailableException(String component) {
        super(HttpStatus.SERVICE_UNAVAILABLE, component + " временно недоступен. Повторите попытку позже");
        code = component.toUpperCase(java.util.Locale.ROOT) + "_UNAVAILABLE";
    }

}
