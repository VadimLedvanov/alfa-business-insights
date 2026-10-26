package alpha.business.insight.core.service;

import alpha.business.insight.core.dto.InsightFragment;
import alpha.business.insight.core.dto.EventReadDto;
import alpha.business.insight.core.dto.CompanyContext;
import alpha.business.insight.core.dto.DialogMessageDto;
import alpha.business.insight.core.dto.EventCandidate;
import alpha.business.insight.core.ml.DocumentAnalyzer;
import alpha.business.insight.core.ml.ComponentUnavailableException;
import alpha.business.insight.core.ml.LlmClient;
import alpha.business.insight.core.model.Event;
import alpha.business.insight.core.model.EventStatus;
import alpha.business.insight.core.model.EventSourceType;
import alpha.business.insight.core.model.Insight;
import alpha.business.insight.core.model.DialogMessage;
import alpha.business.insight.core.model.KnowledgeDocument;
import alpha.business.insight.core.repository.CompanyRepository;
import alpha.business.insight.core.repository.DocumentRepository;
import alpha.business.insight.core.repository.EventRepository;
import alpha.business.insight.core.repository.DialogMessageRepository;
import alpha.business.insight.core.repository.InsightRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
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
import java.util.*;
import java.util.regex.Pattern;

/** Генерирует инсайт и сохраняет результат для текущей версии материалов события. */
@Service
@RequiredArgsConstructor
public class InsightService {
    private static final Logger log = LoggerFactory.getLogger(InsightService.class);
    private static final int MAX_GROUP_MEMBERS_IN_LLM_CONTEXT = 20;
    private final InsightRepository insights;
    private final DialogMessageRepository messages;
    private final CompanyRepository companies;
    private final EventRepository events;
    private final DocumentRepository documents;
    private final CompanyContextService contexts;
    private final DocumentAnalyzer analyzer;
    private final DocumentVectorStore vectors;
    private final LlmClient llm;
    private final InsightValidator validator;
    private final ObjectMapper mapper;
    private static final Set<String> STOP_WORDS = Set.of("test", "fixture", "для", "или", "это", "что", "при", "на", "по", "из", "в", "и", "с", "от", "до", "как", "может", "могут", "новые", "новый", "новая", "рост", "изменение", "изменения");
    private static final Pattern WORD = Pattern.compile("[\\p{L}\\p{N}]{3,}");

    @Transactional(readOnly = true)
    public Map<String, Object> get(String companyId, UUID eventId, Authentication user) {
        contexts.authorize(companyId, user);
        Event event = events.findById(eventId).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.NOT_FOUND, "Событие не найдено"));
        return response(insights.findByCompanyIdAndEventIdAndMaterialsVersion(companyId, eventId, event.getMaterialsVersion())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Сохранённый инсайт не найден")));
    }

    /** Блокировка события и повторная проверка кэша исключают повторные LLM-вызовы параллельных POST. */
    @Transactional
    public Map<String, Object> generate(String companyId, UUID eventId, Authentication user) {
        contexts.authorize(companyId, user);
        Event event = events.findForInsight(eventId).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.NOT_FOUND, "Событие не найдено"));
        Optional<Insight> cached = insights.findByCompanyIdAndEventIdAndMaterialsVersion(companyId, eventId,
                event.getMaterialsVersion());
        if (cached.isPresent()) {
            return response(cached.get());
        }
        UUID traceId = UUID.randomUUID();
        long startedAt = System.nanoTime();
        log.info("Insight generation started traceId={} companyId={} eventId={}", traceId, companyId, eventId);
        Insight generated;
        try {
            generated = analyze(companyId, event, traceId);
        } catch (RuntimeException exception) {
            Object status = exception instanceof ResponseStatusException responseStatus
                    ? responseStatus.getStatusCode().value() : "INTERNAL_ERROR";
            String reason = exception instanceof ResponseStatusException responseStatus
                    ? responseStatus.getReason() : null;
            log.error("Insight generation failed traceId={} companyId={} eventId={} status={} reason={} errorType={} durationMs={}",
                    traceId, companyId, eventId, status, reason, exception.getClass().getSimpleName(),
                    (System.nanoTime() - startedAt) / 1_000_000);
            throw exception;
        }
        log.info("Insight generation completed traceId={} companyId={} eventId={} durationMs={}", traceId,
                companyId, eventId, (System.nanoTime() - startedAt) / 1_000_000);
        String significance = String.valueOf(generated.getPayload().get("significance"));
        String reason = String.valueOf(generated.getPayload().get("significanceReason"));
        String summary = "Инсайт сформирован для компании по событию «" + event.getTitle() + "».";
        Object inferences = generated.getPayload().get("ai_inference");
        if (inferences instanceof List<?> items && !items.isEmpty() && items.getFirst() instanceof Map<?, ?> item
                && item.get("text") instanceof String text && !text.isBlank()) {
            summary = text;
        }
        messages.saveAll(List.of(
                DialogMessage.of(generated, "USER", "Сформировать инсайт по событию для компании."),
                DialogMessage.of(generated, "ASSISTANT", summary + "\n\nЗначимость: " + significance + ". " + reason)));
        return response(generated);
    }

    @Transactional(readOnly = true)
    public List<EventCandidate> topCandidates(String companyId, Authentication user) {
        contexts.authorize(companyId, user);
        CompanyContext context = contexts.getContext(companyId);
        List<Event> candidates = events.findByStatusAndSourceType(EventStatus.READY, EventSourceType.TEXT,
                PageRequest.of(0, 100, Sort.by(Sort.Direction.DESC, "createdAt", "id"))).getContent();
        if (candidates.isEmpty()) {
            return List.of();
        }
        Set<UUID> generated = new HashSet<>(insights.findGeneratedEventIds(companyId,
                candidates.stream().map(Event::getId).toList()));
        String companyText = companyText(context);
        if (companyText.isBlank()) {
            return candidates.stream().limit(3)
                    .map(event -> new EventCandidate(event.getId(), event.getTitle(), event.getSourceType(),
                            event.getStatus(), event.getCreatedAt(), 0, generated.contains(event.getId())))
                    .toList();
        }
        DocumentAnalyzer.EmbeddingComparison comparison;
        try {
            comparison = analyzer.compare(companyText, candidates.stream().map(Event::getSourceRef).toList());
        } catch (RuntimeException exception) {
            if (exception instanceof ResponseStatusException status && status.getStatusCode().is4xxClientError()) {
                throw status;
            }
            throw new ComponentUnavailableException("ML");
        }
        List<EventCandidate> ranked = new java.util.ArrayList<>(candidates.size());
        for (int i = 0; i < candidates.size(); i++) {
            double score = comparison.stub()
                    ? lexicalScore(companyText, candidates.get(i).getSourceRef())
                    : cosine(comparison.companyEmbedding(), comparison.eventEmbeddings().get(i));
            Event event = candidates.get(i);
            ranked.add(new EventCandidate(event.getId(), event.getTitle(), event.getSourceType(), event.getStatus(),
                    event.getCreatedAt(), score, generated.contains(event.getId())));
        }
        return ranked.stream().sorted(Comparator.comparingDouble(EventCandidate::relevanceScore).reversed()
                        .thenComparing(EventCandidate::createdAt, Comparator.reverseOrder())
                        .thenComparing(EventCandidate::id))
                .limit(3).toList();
    }

    @Transactional(readOnly = true)
    public List<DialogMessageDto> messages(String companyId, UUID eventId, Authentication user) {
        contexts.authorize(companyId, user);
        Event event = events.findById(eventId).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.NOT_FOUND, "Событие не найдено"));
        Insight insight = insights.findByCompanyIdAndEventIdAndMaterialsVersion(companyId, eventId,
                        event.getMaterialsVersion())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Сохранённый инсайт не найден"));
        return messages.findByInsightIdOrderByCreatedAtAscIdAsc(insight.getId()).stream()
                .map(DialogMessageDto::from).toList();
    }

    private static String companyText(CompanyContext context) {
        var profile = context.profile();
        List<String> details = new java.util.ArrayList<>();
        for (String value : List.of(profile.mainIndustryCode() == null ? "" : profile.mainIndustryCode(),
                profile.mainIndustryName() == null ? "" : profile.mainIndustryName(),
                profile.organizationType() == null ? "" : profile.organizationType(),
                profile.businessLifecycleStage() == null ? "" : profile.businessLifecycleStage(),
                profile.region() == null ? "" : profile.region())) {
            if (!value.isBlank()) details.add(value);
        }
        if (Boolean.TRUE.equals(profile.hasLoan())) details.add("кредит финансирование заём");
        if (Boolean.TRUE.equals(profile.hasForeignTrade())) details.add("внешняя торговля импорт экспорт");
        if (Boolean.TRUE.equals(profile.hasLeasing())) details.add("лизинг оборудование");
        if (Boolean.TRUE.equals(profile.hasFactoring())) details.add("факторинг дебиторская задолженность");
        if (Boolean.TRUE.equals(profile.hasBankGuarantee())) details.add("банковская гарантия контракт");
        if (Boolean.TRUE.equals(profile.hasSettlementAccount())) details.add("расчётный счёт платежи РКО");
        if (Boolean.TRUE.equals(profile.hasDeposit())) details.add("депозит вклад сбережения");
        if (Boolean.TRUE.equals(profile.hasPayrollProject())) details.add("зарплатный проект зарплата");
        context.counterparties().forEach(c -> {
            if (c.mainIndustryName() != null) details.add(c.mainIndustryName());
        });
        return String.join(" ", details);
    }

    private static double lexicalScore(String companyText, String eventText) {
        Set<String> companyWords = words(companyText);
        Set<String> eventWords = words(eventText);
        if (companyWords.isEmpty() || eventWords.isEmpty()) return 0;
        Set<String> union = new HashSet<>(companyWords);
        union.addAll(eventWords);
        companyWords.retainAll(eventWords);
        return (double) companyWords.size() / union.size();
    }

    private static Set<String> words(String text) {
        var matcher = WORD.matcher(text.toLowerCase(Locale.ROOT));
        Set<String> result = new HashSet<>();
        while (matcher.find()) {
            String word = matcher.group();
            if (!STOP_WORDS.contains(word)) result.add(word);
        }
        return result;
    }

    private static double cosine(List<Float> left, List<Float> right) {
        double dot = 0, leftNorm = 0, rightNorm = 0;
        for (int i = 0; i < left.size(); i++) {
            double a = left.get(i), b = right.get(i);
            dot += a * b;
            leftNorm += a * a;
            rightNorm += b * b;
        }
        return dot / (Math.sqrt(leftNorm) * Math.sqrt(rightNorm));
    }

    private Insight analyze(String companyId, Event event, UUID traceId) {
        UUID eventId = event.getId();
        CompanyContext context = contexts.getContext(companyId);
        List<String> missing = new ArrayList<>(context.missingData());
        ObjectNode external = mapper.createObjectNode();
        ObjectNode company = mapper.valueToTree(context);
        if (context.group().size() > MAX_GROUP_MEMBERS_IN_LLM_CONTEXT) {
            company.set("group", mapper.valueToTree(
                    context.group().subList(0, MAX_GROUP_MEMBERS_IN_LLM_CONTEXT)));
            company.put("groupMemberCount", context.group().size());
            missing.add("Для анализа переданы первые " + MAX_GROUP_MEMBERS_IN_LLM_CONTEXT + " из "
                    + context.group().size() + " компаний группы");
        }
        external.set("company", company);
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
        boolean stubEmbeddingUsed = false;
        for (JsonNode query : plan.path("search_queries")) {
            if (!query.isString() || query.asString().isBlank() || query.asString().length() > 1000) {
                throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Некорректный поисковый запрос LLM");
            }
            if (++queryCount > 3) { break; }
            DocumentAnalyzer.Embedding embedding;
            try { embedding = analyzer.embed(query.asString()); }
            catch (ResponseStatusException exception) {
                throw exception;
            } catch (RuntimeException exception) {
                throw new ComponentUnavailableException("ML");
            }
            stubEmbeddingUsed |= embedding.stub();
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
        if (stubEmbeddingUsed || fragments.values().stream().anyMatch(InsightFragment::stub)) {
            missing.add("[ML STUB] Поисковые векторы синтетические; семантическая релевантность не оценивается");
        }
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

    private Map<String, Object> asMap(ObjectNode node) {
        return mapper.convertValue(node, new TypeReference<>() {
        });
    }

    private Map<String, Object> response(Insight insight) {
        Map<String, Object> result = new LinkedHashMap<>(insight.getPayload());
        Set<String> citedFragments = new HashSet<>();
        for (String field : List.of("facts", "bank_view")) {
            if (result.get(field) instanceof List<?> items) {
                for (Object item : items) {
                    if (item instanceof Map<?, ?> fragment && fragment.get("fragment_id") instanceof String id) {
                        citedFragments.add(id);
                    }
                }
            }
        }
        if (result.get("ai_inference") instanceof List<?> inferences) {
            for (Object inference : inferences) {
                if (inference instanceof Map<?, ?> item && item.get("based_on") instanceof List<?> references) {
                    references.stream().filter(String.class::isInstance).map(String.class::cast)
                            .forEach(citedFragments::add);
                }
            }
        }
        if (result.get("sources") instanceof List<?> sources) {
            result.put("sources", sources.stream().filter(source -> source instanceof Map<?, ?> reference
                    && reference.get("fragment_id") instanceof String id && citedFragments.contains(id)).toList());
        }
        result.put("id", insight.getId());
        result.put("materials_version", insight.getMaterialsVersion());
        result.put("cost_rub", insight.getCostRub());
        return result;
    }
}
