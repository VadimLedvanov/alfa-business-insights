package alpha.business.insight.core.service;

import alpha.business.insight.core.dto.InsightFragment;
import alpha.business.insight.core.dto.EventReadDto;
import alpha.business.insight.core.dto.CompanyContext;
import alpha.business.insight.core.ml.DocumentAnalyzer;
import alpha.business.insight.core.ml.ComponentUnavailableException;
import alpha.business.insight.core.ml.LlmClient;
import alpha.business.insight.core.model.Event;
import alpha.business.insight.core.model.EventSourceType;
import alpha.business.insight.core.model.Insight;
import alpha.business.insight.core.model.KnowledgeDocument;
import alpha.business.insight.core.repository.CompanyRepository;
import alpha.business.insight.core.repository.DocumentRepository;
import alpha.business.insight.core.repository.EventRepository;
import alpha.business.insight.core.repository.InsightRepository;
import alpha.business.insight.core.repository.UserCompanyAccessRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import java.math.BigDecimal;
import java.time.Instant;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Генерирует инсайт и сохраняет результат для текущей версии материалов события. */
@Service
@RequiredArgsConstructor
public class InsightService {
    private final InsightRepository insights;
    private final CompanyRepository companies;
    private final EventRepository events;
    private final DocumentRepository documents;
    private final UserCompanyAccessRepository access;
    private final CompanyContextService contexts;
    private final DocumentAnalyzer analyzer;
    private final DocumentVectorStore vectors;
    private final LlmClient llm;
    private final InsightValidator validator;
    private final ObjectMapper mapper;

    @Transactional(readOnly = true)
    public Map<String, Object> get(String companyId, UUID eventId, Authentication user) {
        authorize(companyId, user);
        Event event = events.findById(eventId).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.NOT_FOUND, "Событие не найдено"));
        return response(insights.findByCompanyIdAndEventIdAndMaterialsVersion(companyId, eventId, event.getMaterialsVersion())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Сохранённый инсайт не найден")));
    }

    /** Блокировка события и повторная проверка кэша исключают повторные LLM-вызовы параллельных POST. */
    @Transactional
    public Map<String, Object> generate(String companyId, UUID eventId, Authentication user) {
        authorize(companyId, user);
        Event event = events.findForInsight(eventId).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.NOT_FOUND, "Событие не найдено"));
        Optional<Insight> cached = insights.findByCompanyIdAndEventIdAndMaterialsVersion(companyId, eventId,
                event.getMaterialsVersion());
        return cached.map(this::response).orElseGet(() -> response(analyze(companyId, event, UUID.randomUUID())));
    }

    private Insight analyze(String companyId, Event event, UUID traceId) {
        UUID eventId = event.getId();
        CompanyContext context = contexts.getContext(companyId);
        List<String> missing = new ArrayList<>(context.missingData());
        ObjectNode external = mapper.createObjectNode();
        external.set("company", mapper.valueToTree(context));
        external.set("event", mapper.valueToTree(EventReadDto.from(event)));
        Map<UUID, InsightFragment> fragments = new LinkedHashMap<>();
        if (event.getSourceType() == EventSourceType.TEXT) {
            UUID fragmentId = UUID.nameUUIDFromBytes((eventId + ":" + event.getMaterialsVersion())
                    .getBytes(StandardCharsets.UTF_8));
            fragments.put(fragmentId, new InsightFragment(fragmentId, null, 1, event.getSourceRef(), "EVENT_TEXT", false));
        } else {
            missing.add("Текст события из URL/PDF не извлечён: обработка ML пока не подключена");
        }
        external.set("fragments", mapper.valueToTree(fragments.values()));
        ObjectNode input = mapper.createObjectNode();
        input.set("external_data", external);
        ObjectNode plan = llm.complete("plan", input);
        if (!plan.path("search_queries").isArray() || plan.path("search_queries").isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "LLM не вернула поисковые запросы");
        }
        int queryCount = 0;
        for (JsonNode query : plan.path("search_queries")) {
            if (!query.isString() || query.asString().isBlank() || query.asString().length() > 1000) {
                throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Некорректный поисковый запрос LLM");
            }
            if (++queryCount > 3) { break; }
            List<Float> embedding;
            try { embedding = analyzer.embed(query.asString()); }
            catch (ResponseStatusException exception) {
                throw exception;
            } catch (RuntimeException exception) {
                throw new ComponentUnavailableException("ML");
            }
            List<InsightFragment> found = vectors.search(embedding);
            List<KnowledgeDocument> metadata = documents.findAllById(found.stream().map(InsightFragment::documentId).distinct().toList());
            Map<UUID, Boolean> available = new LinkedHashMap<>();
            metadata.forEach(document -> available.put(document.getId(), document.isStub()));
            List<InsightFragment> verified = found.stream().filter(fragment -> available.containsKey(fragment.documentId()))
                    .map(fragment -> new InsightFragment(fragment.fragmentId(), fragment.documentId(), fragment.page(),
                            fragment.text(), fragment.materialType(), fragment.stub() || available.get(fragment.documentId())))
                    .toList();
            verified.forEach(fragment -> fragments.putIfAbsent(fragment.fragmentId(), fragment));
        }
        missing.add("[ML STUB] Поисковые векторы синтетические; семантическая релевантность не оценивается");
        external.set("fragments", mapper.valueToTree(fragments.values()));
        input.set("plan", plan);
        ObjectNode draft = llm.complete("draft", input);
        input.set("draft", draft);
        ObjectNode critic = llm.complete("critic", input);
        input.set("critic", critic);
        ObjectNode result = critic.path("complete").asBoolean() ? draft : llm.complete("revision", input);
        missing.add("Стоимость генерации инсайта пока не рассчитывается в MVP");
        ObjectNode validated = validator.validate(result, List.copyOf(fragments.values()), missing,
                traceId, BigDecimal.ZERO);
        Insight insight = new Insight();
        insight.setCompany(companies.getReferenceById(companyId));
        insight.setEvent(event);
        insight.setMaterialsVersion(event.getMaterialsVersion());
        insight.setPayload(asMap(validated));
        insight.setCostRub(BigDecimal.ZERO);
        insight.setCreatedAt(Instant.now());
        return insights.save(insight);
    }

    private void authorize(String companyId, Authentication user) {
        if (!companies.existsById(companyId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Компания не найдена");
        }
        boolean admin = user.getAuthorities().stream().anyMatch(role -> role.getAuthority().equals("ROLE_SYSTEM_ADMIN"));
        if (!admin && !access.hasAccess(user.getName(), companyId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Нет доступа к компании");
        }
    }

    private Map<String, Object> asMap(ObjectNode node) {
        return mapper.convertValue(node, new TypeReference<Map<String, Object>>() {});
    }

    private Map<String, Object> response(Insight insight) {
        Map<String, Object> result = new LinkedHashMap<>(insight.getPayload());
        result.put("id", insight.getId());
        result.put("materials_version", insight.getMaterialsVersion());
        result.put("cost_rub", insight.getCostRub());
        return result;
    }
}
