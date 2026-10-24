package alpha.business.insight.core.document;

import alpha.business.insight.core.dto.DocumentMetadata;
import alpha.business.insight.core.ml.DocumentAnalysis;
import alpha.business.insight.core.ml.DocumentAnalyzer;
import alpha.business.insight.core.ml.DocumentChunk;
import alpha.business.insight.core.ml.StubDocumentAnalyzer;
import alpha.business.insight.core.service.DocumentService;
import alpha.business.insight.core.service.DocumentStorage;
import alpha.business.insight.core.service.DocumentVectorStore;
import io.qdrant.client.QdrantClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.server.ResponseStatusException;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static alpha.business.insight.core.service.DocumentVectorStore.STUB_COLLECTION;
import static io.qdrant.client.PointIdFactory.id;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Сквозная проверка: оригинал в файлах, метаданные в PostgreSQL, тексты чанков и векторы в Qdrant.
 */
@SpringBootTest(properties = {
        "spring.security.oauth2.resourceserver.jwt.issuer-uri=http://localhost/test-realm",
        "spring.security.oauth2.resourceserver.jwt.jwk-set-uri=http://localhost/test-realm/certs"
})
@AutoConfigureMockMvc
@Testcontainers
class DocumentUploadIntegrationTest {
    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18");
    @Container
    static final GenericContainer<?> QDRANT = new GenericContainer<>("qdrant/qdrant:v1.19.0")
            .withExposedPorts(6333, 6334).waitingFor(Wait.forHttp("/readyz").forPort(6333));
    static final Path STORAGE = createStorage();
    static final byte[] PDF = "%PDF-1.4\n% Signature-only fixture\n%%EOF".getBytes(StandardCharsets.UTF_8);

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("qdrant.host", QDRANT::getHost);
        registry.add("qdrant.grpc-port", () -> QDRANT.getMappedPort(6334));
        registry.add("documents.storage-path", STORAGE::toString);
    }

    @Autowired DocumentService service;
    @Autowired DocumentStorage storage;
    @Autowired JdbcTemplate jdbc;
    @Autowired QdrantClient qdrant;
    @Autowired MockMvc mvc;
    @MockitoBean DocumentAnalyzer analyzer;
    @MockitoBean JwtDecoder decoder;
    @MockitoSpyBean DocumentVectorStore vectors;
    final AtomicReference<UUID> lastDocumentId = new AtomicReference<>();

    @BeforeEach
    void prepare() throws Exception {
        reset(vectors);
        when(analyzer.analyze(any(), any())).thenAnswer(invocation -> {
            lastDocumentId.set(invocation.getArgument(0));
            return new StubDocumentAnalyzer().analyze(invocation.getArgument(0), invocation.getArgument(1));
        });
        jdbc.execute("TRUNCATE documents");
        try (var files = Files.walk(STORAGE)) {
            for (Path file : files.sorted(java.util.Comparator.reverseOrder()).toList()) {
                if (!file.equals(STORAGE)) {
                    Files.delete(file);
                }
            }
        }
        if (qdrant.collectionExistsAsync(STUB_COLLECTION).get()) {
            qdrant.deleteCollectionAsync(STUB_COLLECTION).get();
        }
    }

    @Test
    void openApiDescribesMetadataWithoutPostgresFragmentFields() throws Exception {
        mvc.perform(get("/api/v3/api-docs").contextPath("/api"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.components.schemas.DocumentMetadata.description").isNotEmpty())
                .andExpect(jsonPath("$.components.schemas.DocumentMetadata.properties.chunkCount.description").isNotEmpty())
                .andExpect(jsonPath("$.components.schemas.DocumentMetadata.properties.sha256.maxLength").value(64))
                .andExpect(jsonPath("$.components.schemas.DocumentMetadata.properties.filePath").doesNotExist())
                .andExpect(jsonPath("$.components.schemas.DocumentMetadata.properties.factCount").doesNotExist())
                .andExpect(jsonPath("$.components.schemas.DocumentMetadata.properties.fragmentCount").doesNotExist())
                .andExpect(jsonPath("$.paths['/admin/documents'].post.parameters[?(@.name == 'title')].description").isNotEmpty());
    }

    @Test
    void uploadStoresOnlyMetadataInPostgresAndTextAlongsideVectorInQdrant() throws Exception {
        mvc.perform(multipart("/api/admin/documents").contextPath("/api").file(pdf())
                        .param("title", "Аналитика банка").param("publicationDate", "2026-10-21")
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_SYSTEM_ADMIN"))))
                .andExpect(status().isCreated()).andExpect(header().exists("Location"))
                .andExpect(jsonPath("$.title").value("Аналитика банка"))
                .andExpect(jsonPath("$.stub").value(true))
                .andExpect(jsonPath("$.chunkCount").value(1));
        UUID documentId = lastDocumentId.get();
        assertThat(STORAGE.resolve(storage.relativePath(documentId))).hasBinaryContent(PDF);
        assertThat(jdbc.queryForObject("SELECT file_path FROM documents WHERE id = ?", String.class, documentId))
                .isEqualTo(storage.relativePath(documentId));
        var points = qdrant.retrieveAsync(STUB_COLLECTION,
                id(DocumentVectorStore.chunkId(documentId, 0)), true, true, null).get();
        assertThat(points).hasSize(1);
        var point = points.getFirst();
        assertThat(point.getPayloadMap().get("documentId").getStringValue()).isEqualTo(documentId.toString());
        assertThat(point.getPayloadMap().get("text").getStringValue()).contains("[ML STUB]");
        assertThat(point.getPayloadMap().get("page").getIntegerValue()).isEqualTo(1);
        assertThat(point.getPayloadMap().get("publicationDate").getStringValue()).isEqualTo("2026-10-21");
        assertThat(point.hasVectors()).isTrue();
        assertThat(service.get(documentId).chunkCount()).isEqualTo(1);
        assertThat(jdbc.queryForList("""
                SELECT table_name FROM information_schema.tables WHERE table_schema = current_schema()
                    AND table_name IN ('fragments', 'facts', 'document_upload_cleanup')
                """, String.class)).isEmpty();
        for (String role : List.of("ROLE_SYSTEM_ADMIN", "ROLE_COMPANY_DIRECTOR")) {
            mvc.perform(get("/api/documents/" + documentId).contextPath("/api")
                            .with(jwt().authorities(new SimpleGrantedAuthority(role))))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.chunkCount").value(1));
            mvc.perform(get("/api/documents/" + documentId + "/file").contextPath("/api")
                            .with(jwt().authorities(new SimpleGrantedAuthority(role))))
                    .andExpect(status().isOk()).andExpect(content().contentType("application/pdf"))
                    .andExpect(content().bytes(PDF));
        }
    }

    @Test
    void multipleChunksPreserveTheirTextsPagesAndIndependentIds() throws Exception {
        when(analyzer.analyze(any(), any())).thenAnswer(invocation -> {
            var sample = new StubDocumentAnalyzer().analyze(invocation.getArgument(0), invocation.getArgument(1));
            return new DocumentAnalysis(sample.documentId(), 1, true, "stub-v1", 8,
                    List.of(new DocumentChunk(0, 1, "Первая страница", sample.chunks().getFirst().embedding()),
                            new DocumentChunk(1, 3, "Третья страница", sample.chunks().getFirst().embedding())));
        });
        var document = service.upload(pdf(), null, null);
        assertThat(document.title()).isEqualTo("report.pdf");
        assertThat(service.get(document.documentId()).chunkCount()).isEqualTo(2);
        var points = qdrant.retrieveAsync(STUB_COLLECTION,
                id(DocumentVectorStore.chunkId(document.documentId(), 1)), null).get();
        assertThat(points.getFirst().getPayloadMap().get("text").getStringValue()).isEqualTo("Третья страница");
        assertThat(points.getFirst().getPayloadMap().get("page").getIntegerValue()).isEqualTo(3);
    }

    @Test
    void uploadRequiresAdminAndUnknownDocumentReturns404() throws Exception {
        mvc.perform(multipart("/api/admin/documents").contextPath("/api").file(pdf()))
                .andExpect(status().isUnauthorized());
        mvc.perform(multipart("/api/admin/documents").contextPath("/api").file(pdf())
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_COMPANY_DIRECTOR"))))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/documents/" + UUID.randomUUID()).contextPath("/api")
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_COMPANY_DIRECTOR"))))
                .andExpect(status().isNotFound());
        assertThat(documentCount()).isZero();
    }

    @Test
    void rejectsEmptyNonPdfAndOversizedFileBeforeCreatingArtifacts() throws Exception {
        for (int size : List.of(0, 3, 20 * 1024 * 1024 + 1)) {
            mvc.perform(multipart("/api/admin/documents").contextPath("/api")
                            .file(new MockMultipartFile("file", "test.pdf", "application/pdf", new byte[size]))
                            .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_SYSTEM_ADMIN"))))
                    .andExpect(status().is(size == 0 ? 400 : size == 3 ? 415 : 413));
        }
        assertThat(documentCount()).isZero();
        assertNoFiles();
    }

    @ParameterizedTest
    @ValueSource(strings = {"document", "ordinal", "dimension", "nan", "empty", "exception"})
    void invalidMlResponseCancelsUpload(String fault) throws Exception {
        when(analyzer.analyze(any(), any())).thenAnswer(invocation -> {
            if (fault.equals("exception")) {
                throw new IllegalStateException("ML unavailable");
            }
            UUID documentId = invocation.getArgument(0);
            var chunk = new DocumentChunk(0, 1, "Тест", fault.equals("nan")
                    ? List.of(Float.NaN, 0f, 0f, 0f, 0f, 0f, 0f, 0f)
                    : List.of(1f, 0f, 0f, 0f, 0f, 0f, 0f, 0f));
            return new DocumentAnalysis(fault.equals("document") ? UUID.randomUUID() : documentId, 1, true,
                    "stub-v1", fault.equals("dimension") ? 3 : 8,
                    fault.equals("empty") ? List.of() : fault.equals("ordinal") ? List.of(chunk, chunk) : List.of(chunk));
        });
        mvc.perform(multipart("/api/admin/documents").contextPath("/api").file(pdf())
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_SYSTEM_ADMIN"))))
                .andExpect(status().is(fault.equals("exception") ? 503 : 502));
        assertThat(documentCount()).isZero();
        assertNoFiles();
    }

    @Test
    void partialQdrantWriteDeletesChunksAndOriginalWithoutSavingMetadata() throws Exception {
        doAnswer(invocation -> {
            invocation.callRealMethod();
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Partial write");
        }).when(vectors).index(any(), any(), anyLong());
        assertThatThrownBy(() -> service.upload(pdf(), null, null)).isInstanceOf(ResponseStatusException.class);
        assertThat(documentCount()).isZero();
        assertThat(qdrant.retrieveAsync(STUB_COLLECTION,
                id(DocumentVectorStore.chunkId(lastDocumentId.get(), 0)), null).get()).isEmpty();
        assertNoFiles();
    }

    @Test
    void metadataCommitFailureDeletesAlreadyIndexedChunksAndOriginal() throws Exception {
        jdbc.execute("""
                CREATE FUNCTION fail_document_commit() RETURNS trigger LANGUAGE plpgsql AS $$
                BEGIN RAISE EXCEPTION 'simulated commit failure'; END $$
                """);
        jdbc.execute("""
                CREATE CONSTRAINT TRIGGER fail_document_commit AFTER INSERT ON documents
                DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION fail_document_commit()
                """);
        try {
            assertThatThrownBy(() -> service.upload(pdf(), null, null)).isInstanceOf(RuntimeException.class);
            assertThat(documentCount()).isZero();
            assertThat(qdrant.retrieveAsync(STUB_COLLECTION,
                    id(DocumentVectorStore.chunkId(lastDocumentId.get(), 0)), null).get()).isEmpty();
            assertNoFiles();
        } finally {
            jdbc.execute("DROP TRIGGER fail_document_commit ON documents");
            jdbc.execute("DROP FUNCTION fail_document_commit()");
        }
    }

    @Test
    void concurrentUploadsKeepDocumentAndChunkIdsIndependent() throws Exception {
        var futures = java.util.stream.IntStream.range(0, 4)
                .mapToObj(i -> new java.util.concurrent.CompletableFuture<DocumentMetadata>()).toList();
        for (int i = 0; i < futures.size(); i++) {
            int index = i;
            Thread.ofPlatform().start(() -> {
                try {
                    futures.get(index).complete(service.upload(pdf(), "Report " + index, null));
                } catch (Throwable failure) {
                    futures.get(index).completeExceptionally(failure);
                }
            });
        }
        for (var future : futures) {
            var document = future.get(30, java.util.concurrent.TimeUnit.SECONDS);
            assertThat(service.get(document.documentId()).title()).isEqualTo(document.title());
            assertThat(qdrant.retrieveAsync(STUB_COLLECTION,
                    id(DocumentVectorStore.chunkId(document.documentId(), 0)), null).get()).hasSize(1);
        }
        assertThat(documentCount()).isEqualTo(4);
    }

    private long documentCount() {
        return jdbc.queryForObject("SELECT count(*) FROM documents", Long.class);
    }

    private void assertNoFiles() throws Exception {
        try (var files = Files.walk(STORAGE)) {
            assertThat(files.filter(Files::isRegularFile).toList()).isEmpty();
        }
    }

    private static MockMultipartFile pdf() {
        return new MockMultipartFile("file", "report.pdf", "application/pdf", PDF);
    }

    private static Path createStorage() {
        try {
            return Files.createTempDirectory("knowledge-documents-test-");
        } catch (java.io.IOException exception) {
            throw new java.io.UncheckedIOException(exception);
        }
    }
}
