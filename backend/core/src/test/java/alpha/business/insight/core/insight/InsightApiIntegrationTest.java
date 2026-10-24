package alpha.business.insight.core.insight;

import alpha.business.insight.core.ml.LlmClient;
import alpha.business.insight.core.ml.DocumentAnalysis;
import alpha.business.insight.core.ml.StubDocumentAnalyzer;
import alpha.business.insight.core.model.KnowledgeDocument;
import alpha.business.insight.core.repository.DocumentRepository;
import alpha.business.insight.core.service.DocumentVectorStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.util.UUID;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "spring.security.oauth2.resourceserver.jwt.issuer-uri=http://localhost/test-realm",
        "spring.security.oauth2.resourceserver.jwt.jwk-set-uri=http://localhost/test-realm/certs",
        "spring.ai.model.chat=none", "ml.stub=true",
        "spring.datasource.hikari.maximum-pool-size=1", "spring.datasource.hikari.connection-timeout=1000"
})
@AutoConfigureMockMvc
@Testcontainers
class InsightApiIntegrationTest {
    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18");
    @Container
    static final GenericContainer<?> QDRANT = new GenericContainer<>("qdrant/qdrant:v1.19.0")
            .withExposedPorts(6333, 6334).waitingFor(Wait.forHttp("/readyz").forPort(6333));

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("qdrant.host", QDRANT::getHost);
        registry.add("qdrant.grpc-port", () -> QDRANT.getMappedPort(6334));
    }

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper mapper;
    @MockitoBean JwtDecoder decoder;
    @MockitoSpyBean LlmClient llm;
    @Autowired DocumentVectorStore vectors;
    @Autowired DocumentRepository documents;
    String company;
    UUID event;

    @BeforeEach
    void prepare() {
        company = "insight_" + UUID.randomUUID();
        event = UUID.randomUUID();
        jdbc.update("INSERT INTO companies (company_id, profile_status) VALUES (?, 'MISSING')", company);
        jdbc.update("INSERT INTO events (id, title, source_type, source_ref, status) "
                + "VALUES (?, 'Снижение ставки', 'TEXT', 'Ставка снижена', 'READY')", event);
        jdbc.update("INSERT INTO user_company_access (user_id, company_id) VALUES ('director', ?)", company);
    }

    @Test
    void repeatedPostAndGetReturnSameInsightWithoutNewLlmCalls() throws Exception {
        JsonNode first = generate();
        assertThat(first.path("trace_id").asString()).isNotBlank();
        assertThat(first.path("bank_view").isArray()).isTrue();
        assertThat(first.path("bank_view").size()).isZero();
        assertThat(first.path("missing_data").toString()).contains("банк");
        assertThat(first.path("cost_rub").decimalValue()).isZero();
        for (String field : new String[]{"facts", "ai_inference", "hypotheses", "features_used", "risks",
                "opportunities", "actions", "missing_data", "sources"}) {
            assertThat(first.path(field).isArray()).as(field).isTrue();
        }
        assertThat(generate()).isEqualTo(first);
        JsonNode read = mapper.readTree(mvc.perform(get(path()).contextPath("/api").with(director()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(read).isEqualTo(first);
        verify(llm, times(4)).complete(anyString(), any());
    }

    @Test
    void newMaterialsVersionRequiresNewGeneration() throws Exception {
        JsonNode first = generate();
        jdbc.update("UPDATE events SET materials_version=2 WHERE id=?", event);
        mvc.perform(get(path()).contextPath("/api").with(director())).andExpect(status().isNotFound());
        JsonNode second = generate();
        assertThat(second.path("id")).isNotEqualTo(first.path("id"));
        assertThat(second.path("trace_id")).isNotEqualTo(first.path("trace_id"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM insights WHERE company_id=?", Integer.class, company))
                .isEqualTo(2);
    }

    @Test
    void concurrentPostsShareOneGeneration() throws Exception {
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<JsonNode> first = executor.submit(this::generate);
            Future<JsonNode> second = executor.submit(this::generate);
            assertThat(first.get()).isEqualTo(second.get());
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM insights WHERE company_id=?", Integer.class, company))
                .isEqualTo(1);
    }

    @Test
    void missingSavedInsightReturns404() throws Exception {
        mvc.perform(get(path()).contextPath("/api").with(director())).andExpect(status().isNotFound());
    }

    @Test
    void rejectsAnonymousAndDirectorWithoutCompanyAccess() throws Exception {
        mvc.perform(post(path()).contextPath("/api")).andExpect(status().isUnauthorized());
        jdbc.update("DELETE FROM user_company_access WHERE company_id=?", company);
        mvc.perform(post(path()).contextPath("/api").with(director())).andExpect(status().isForbidden());
        mvc.perform(get(path()).contextPath("/api").with(director())).andExpect(status().isForbidden());
    }

    @Test
    void administratorCanGenerateWithoutCompanyGrant() throws Exception {
        jdbc.update("DELETE FROM user_company_access WHERE company_id=?", company);
        mvc.perform(post(path()).contextPath("/api").with(jwt().authorities(
                new SimpleGrantedAuthority("ROLE_SYSTEM_ADMIN")))).andExpect(status().isOk());
    }

    @Test
    void unknownCompanyOrEventReturns404() throws Exception {
        mvc.perform(post("/api/companies/unknown/events/" + event + "/insight").contextPath("/api")
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_SYSTEM_ADMIN"))))
                .andExpect(status().isNotFound());
        event = UUID.randomUUID();
        mvc.perform(post(path()).contextPath("/api").with(director())).andExpect(status().isNotFound());
    }

    @Test
    void qdrantFragmentsReachPromptButSyntheticBankViewIsRemoved() throws Exception {
        UUID documentId = UUID.randomUUID();
        DocumentAnalysis analysis = new StubDocumentAnalyzer().analyze(documentId, new byte[0]);
        vectors.index(analysis, null, System.nanoTime() + Duration.ofSeconds(10).toNanos());
        documents.saveAndFlush(new KnowledgeDocument(documentId, "Банковский PDF", "test.pdf", "test.pdf",
                1, "0".repeat(64), Instant.now(), null, 1, 1, "stub-v1", 8, true));
        String fragmentId = DocumentVectorStore.chunkId(documentId, 0).toString();
        String fragmentText = analysis.chunks().getFirst().text();
        doAnswer(invocation -> {
            ObjectNode input = invocation.getArgument(1);
            assertThat(input.path("external_data").path("fragments").toString()).contains(fragmentId, fragmentText);
            ObjectNode completion = (ObjectNode) invocation.callRealMethod();
            completion.withArray("bank_view").addObject().put("text", "Выдуманная позиция банка")
                    .put("fragment_id", fragmentId);
            completion.withArray("facts").addObject().put("text", "Несуществующий фрагмент")
                    .put("fragment_id", UUID.randomUUID().toString());
            completion.withArray("sources").addObject().put("fragment_id", fragmentId)
                    .put("text", fragmentText);
            completion.putArray("fragments").addObject().put("text", fragmentText);
            return completion;
        }).when(llm).complete(eq("revision"), any());
        JsonNode result = generate();
        assertThat(result.path("bank_view").isEmpty()).isTrue();
        assertThat(result.path("facts").isEmpty()).isTrue();
        assertThat(result.path("sources").toString()).contains(fragmentId, "#page=1").doesNotContain(fragmentText);
        assertThat(result.path("sources").findValues("text")).isEmpty();
        assertThat(jdbc.queryForObject("SELECT payload::text FROM insights WHERE company_id=?", String.class, company))
                .doesNotContain(fragmentText, "Ставка снижена");
        assertThat(result.path("missing_data").toString()).contains("банк", "STUB");
    }

    @Test
    void failedLlmDoesNotCacheIncompleteInsight() throws Exception {
        doAnswer(invocation -> {
            ObjectNode completion = (ObjectNode) invocation.callRealMethod();
            completion.withArray("issues").addObject().put("source_fragment", "Ставка снижена");
            return completion;
        }).when(llm).complete(eq("critic"), any());
        doThrow(new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.BAD_GATEWAY,
                "LLM недоступна")).when(llm).complete(eq("revision"), any());
        mvc.perform(post(path()).contextPath("/api").with(director()))
                .andExpect(status().isBadGateway());
        mvc.perform(get(path()).contextPath("/api").with(director())).andExpect(status().isNotFound());
        doCallRealMethod().when(llm).complete(eq("revision"), any());
        generate();
    }

    private JsonNode generate() throws Exception {
        return mapper.readTree(mvc.perform(post(path()).contextPath("/api").with(director()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.id").isNotEmpty())
                .andReturn().getResponse().getContentAsString());
    }

    private String path() { return "/api/companies/" + company + "/events/" + event + "/insight"; }
    private RequestPostProcessor director() {
        return jwt().jwt(token -> token.subject("director"))
                .authorities(new SimpleGrantedAuthority("ROLE_COMPANY_DIRECTOR"));
    }
}
