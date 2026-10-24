package alpha.business.insight.core.controller;

import alpha.business.insight.core.dto.CompanyContext;
import alpha.business.insight.core.service.CompanyContextService;
import alpha.business.insight.core.service.InsightService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import lombok.RequiredArgsConstructor;
import java.util.Map;
import java.util.UUID;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/companies")
public class CompanyController {
    private final CompanyContextService service;
    private final InsightService insights;

    @GetMapping("/{id}")
    @Operation(summary = "Контекст компании: профиль, группа и крупнейшие контрагенты",
            description = "Контрагенты ранжируются по сумме входящих и исходящих платежей. "
                    + "Доли рассчитываются от полного оборота; собственные платежи исключаются.")
    @ApiResponse(responseCode = "200", description = "Контекст компании, включая признаки недостающих данных")
    @ApiResponse(responseCode = "404", description = "Неизвестный идентификатор компании")
    public CompanyContext getCompany(@PathVariable String id) {
        return service.getContext(id);
    }

    @PostMapping("/{id}/events/{eventId}/insight")
    @Operation(summary = "Сгенерировать инсайт или получить сохранённый для текущей версии материалов",
            description = "Не более четырёх вызовов LLM. Повторный POST возвращает сохранённый результат.")
    @ApiResponse(responseCode = "200", description = "Инсайт по JSON-контракту")
    public Map<String, Object> generateInsight(@PathVariable String id, @PathVariable UUID eventId,
                                               Authentication user) {
        return insights.generate(id, eventId, user);
    }

    @GetMapping("/{id}/events/{eventId}/insight")
    @Operation(summary = "Получить сохранённый инсайт текущей версии без обращения к LLM")
    @ApiResponse(responseCode = "200", description = "Сохранённый инсайт")
    public Map<String, Object> getInsight(@PathVariable String id, @PathVariable UUID eventId,
                                          Authentication user) {
        return insights.get(id, eventId, user);
    }
}
