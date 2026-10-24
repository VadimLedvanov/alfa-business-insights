package alpha.business.insight.core.document;

import static alpha.business.insight.core.service.DocumentVectorStore.STUB_COLLECTION;
import static io.qdrant.client.PointIdFactory.id;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasKey;
import static org.hamcrest.Matchers.isEmptyString;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;

import alpha.business.insight.core.dto.DocumentMetadata;
import alpha.business.insight.core.ml.DocumentAnalysis;
import alpha.business.insight.core.ml.DocumentAnalyzer;
import alpha.business.insight.core.ml.DocumentChunk;
import alpha.business.insight.core.ml.StubDocumentAnalyzer;
import alpha.business.insight.core.qa.connectors.MockMvcRestAssured;
import alpha.business.insight.core.qa.helpers.SizedPdfFixtures;
import alpha.business.insight.core.qa.helpers.TestEvidence;
import alpha.business.insight.core.service.DocumentService;
import alpha.business.insight.core.service.DocumentStorage;
import alpha.business.insight.core.service.DocumentVectorStore;
import io.qdrant.client.QdrantClient;
import io.qdrant.client.grpc.JsonWithInt.Value;
import io.qdrant.client.grpc.Points.RetrievedPoint;
import io.restassured.module.mockmvc.specification.MockMvcRequestSpecification;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
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

/** Проверяет загрузку документов и согласованность файлов, PostgreSQL и Qdrant. */
@SpringBootTest(
        properties = {
            "spring.security.oauth2.resourceserver.jwt.issuer-uri=http://localhost/test-realm",
            "spring.security.oauth2.resourceserver.jwt.jwk-set-uri=http://localhost/test-realm/certs"
        })
@AutoConfigureMockMvc
@Testcontainers
@Tag("integration")
@Tag("documents")
class DocumentUploadIntegrationTest {
    @Container static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18");

    @Container
    static final GenericContainer<?> QDRANT =
            new GenericContainer<>("qdrant/qdrant:v1.19.0")
                    .withExposedPorts(6333, 6334)
                    .waitingFor(Wait.forHttp("/readyz").forPort(6333));

    /** Создаёт изолированную временную директорию для документов. */
    static final Path STORAGE = createStorage();

    static final byte[] PDF =
            "%PDF-1.4\n% Signature-only fixture\n%%EOF".getBytes(StandardCharsets.UTF_8);

    /** Подключает PostgreSQL, Qdrant и временное хранилище к Spring-контексту. */
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

    /** Очищает зависимости и настраивает анализатор перед каждым тестом. */
    @BeforeEach
    void prepare() throws Exception {
        reset(vectors);
        when(analyzer.analyze(any(), any()))
                .thenAnswer(
                        invocation -> {
                            lastDocumentId.set(invocation.getArgument(0));
                            return new StubDocumentAnalyzer()
                                    .analyze(invocation.getArgument(0), invocation.getArgument(1));
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

    /** Проверяет описание метаданных документа в OpenAPI. */
    @Test
    void openApiDescribesMetadataWithoutPostgresFragmentFields() throws Exception {
        // Действие и проверки: получить опубликованное описание API.
        request()
                .when()
                .get("/api/v3/api-docs")
                .then()
                .statusCode(200)
                .body("components.schemas.DocumentMetadata.description", notNullValue())
                .body("components.schemas.DocumentMetadata.description", not(isEmptyString()))
                .body(
                        "components.schemas.DocumentMetadata.properties.chunkCount.description",
                        notNullValue())
                .body(
                        "components.schemas.DocumentMetadata.properties.chunkCount.description",
                        not(isEmptyString()))
                .body(
                        "components.schemas.DocumentMetadata.properties.sha256.maxLength",
                        equalTo(64))
                .body(
                        "components.schemas.DocumentMetadata.properties.sizeBytes.maximum",
                        equalTo(52_428_800))
                .body("components.schemas.DocumentMetadata.properties", not(hasKey("filePath")))
                .body("components.schemas.DocumentMetadata.properties", not(hasKey("factCount")))
                .body(
                        "components.schemas.DocumentMetadata.properties",
                        not(hasKey("fragmentCount")))
                .body(
                        "paths['/admin/documents'].post.description",
                        containsString("Максимум 50 МБ"))
                .body(
                        "paths['/admin/documents'].post.responses['413'].description",
                        equalTo("Файл превышает 50 МБ"))
                .body(
                        "paths['/admin/documents'].post.parameters.find { it.name == 'title' }.description",
                        notNullValue())
                .body(
                        "paths['/admin/documents'].post.parameters.find { it.name == 'title' }.description",
                        not(isEmptyString()));
    }

    /** Проверяет загрузку PDF, метаданные, исходный файл и индексированный чанк. */
    @Test
    void uploadStoresOnlyMetadataInPostgresAndTextAlongsideVectorInQdrant() throws Exception {
        // Подготовка и действие: отправить PDF с названием и датой публикации.
        request()
                .multiPart("file", "report.pdf", PDF, "application/pdf")
                .param("title", "Аналитика банка")
                .param("publicationDate", "2026-10-21")
                .postProcessors(jwt().authorities(new SimpleGrantedAuthority("ROLE_SYSTEM_ADMIN")))
                .when()
                .post("/api/admin/documents")
                .then()
                .statusCode(201)
                .header("Location", notNullValue())
                .body("title", equalTo("Аналитика банка"))
                .body("stub", equalTo(true))
                .body("chunkCount", equalTo(1));

        // Проверки: сверить сохранённый файл, метаданные и точку индекса.
        UUID documentId = lastDocumentId.get();
        assertThat(STORAGE.resolve(storage.relativePath(documentId))).hasBinaryContent(PDF);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT file_path FROM documents WHERE id = ?",
                                String.class,
                                documentId))
                .isEqualTo(storage.relativePath(documentId));
        var points =
                qdrant.retrieveAsync(
                                STUB_COLLECTION,
                                id(DocumentVectorStore.chunkId(documentId, 0)),
                                true,
                                true,
                                null)
                        .get();
        assertThat(points).hasSize(1);
        var point = points.getFirst();
        assertThat(point.getPayloadMap().get("documentId").getStringValue())
                .isEqualTo(documentId.toString());
        assertThat(point.getPayloadMap().get("text").getStringValue()).contains("[ML STUB]");
        assertThat(point.getPayloadMap().get("page").getIntegerValue()).isEqualTo(1);
        assertThat(point.getPayloadMap().get("publicationDate").getStringValue())
                .isEqualTo("2026-10-21");
        assertThat(point.hasVectors()).isTrue();
        assertThat(service.get(documentId).chunkCount()).isEqualTo(1);
        assertThat(
                        jdbc.queryForList(
                                """
                SELECT table_name FROM information_schema.tables WHERE table_schema = current_schema()
                    AND table_name IN ('fragments', 'facts', 'document_upload_cleanup')
                """,
                                String.class))
                .isEmpty();
        // Обе роли могут читать метаданные и исходный PDF.
        for (String role : List.of("ROLE_SYSTEM_ADMIN", "ROLE_COMPANY_DIRECTOR")) {
            request()
                    .postProcessors(jwt().authorities(new SimpleGrantedAuthority(role)))
                    .when()
                    .get("/api/documents/" + documentId)
                    .then()
                    .statusCode(200)
                    .body("chunkCount", equalTo(1));
            var downloaded =
                    request()
                            .postProcessors(jwt().authorities(new SimpleGrantedAuthority(role)))
                            .when()
                            .get("/api/documents/" + documentId + "/file")
                            .then()
                            .statusCode(200)
                            .contentType("application/pdf")
                            .extract()
                            .response();
            assertThat(downloaded.asByteArray()).containsExactly(PDF);
        }
    }

    /** Проверяет сохранение текста и номера страницы для нескольких чанков. */
    @Test
    void multipleChunksPreserveTheirTextsPagesAndIndependentIds() throws Exception {
        // Подготовка: задать два чанка на физических страницах 1 и 3.
        when(analyzer.analyze(any(), any()))
                .thenAnswer(
                        invocation -> {
                            var sample =
                                    new StubDocumentAnalyzer()
                                            .analyze(
                                                    invocation.getArgument(0),
                                                    invocation.getArgument(1));
                            return new DocumentAnalysis(
                                    sample.documentId(),
                                    1,
                                    true,
                                    "stub-v1",
                                    8,
                                    List.of(
                                            new DocumentChunk(
                                                    0,
                                                    1,
                                                    "Первая страница",
                                                    sample.chunks().getFirst().embedding()),
                                            new DocumentChunk(
                                                    1,
                                                    3,
                                                    "Третья страница",
                                                    sample.chunks().getFirst().embedding())));
                        });
        TestEvidence.input("PDF для загрузки", PDF);

        // Действие: загрузить PDF и прочитать метаданные и второй чанк.
        var document = service.upload(pdf(), null, null);
        var persistedMetadata = service.get(document.documentId());
        var points =
                qdrant.retrieveAsync(
                                STUB_COLLECTION,
                                id(DocumentVectorStore.chunkId(document.documentId(), 1)),
                                true,
                                true,
                                null)
                        .get();
        List<Map<String, Object>> indexedPayloads =
                points.stream().map(this::retrievedPointEvidence).toList();
        TestEvidence.output("Загруженные метаданные", document);
        TestEvidence.output("Метаданные из PostgreSQL", persistedMetadata);
        TestEvidence.output("Полученные payload чанков из Qdrant", indexedPayloads);

        // Проверки: подтвердить название, число чанков, текст и номер страницы.
        assertThat(document.title()).isEqualTo("report.pdf");
        assertThat(persistedMetadata.chunkCount()).isEqualTo(2);
        assertThat(points.getFirst().getPayloadMap().get("text").getStringValue())
                .isEqualTo("Третья страница");
        assertThat(points.getFirst().getPayloadMap().get("page").getIntegerValue()).isEqualTo(3);
    }

    /** Проверяет права на загрузку и ответ для неизвестного документа. */
    @Test
    void uploadRequiresAdminAndUnknownDocumentReturns404() throws Exception {
        // Проверки отказа для анонимной загрузки и роли директора.
        request()
                .multiPart("file", "report.pdf", PDF, "application/pdf")
                .when()
                .post("/api/admin/documents")
                .then()
                .statusCode(401);
        request()
                .multiPart("file", "report.pdf", PDF, "application/pdf")
                .postProcessors(
                        jwt().authorities(new SimpleGrantedAuthority("ROLE_COMPANY_DIRECTOR")))
                .when()
                .post("/api/admin/documents")
                .then()
                .statusCode(403);
        // Действие и проверка: запросить несуществующий документ.
        request()
                .postProcessors(
                        jwt().authorities(new SimpleGrantedAuthority("ROLE_COMPANY_DIRECTOR")))
                .when()
                .get("/api/documents/" + UUID.randomUUID())
                .then()
                .statusCode(404);
        assertThat(documentCount()).isZero();
    }

    /** Проверяет отклонение пустых, неподходящих и слишком больших файлов. */
    @Test
    void rejectsEmptyNonPdfAndOversizedFileBeforeCreatingArtifacts() throws Exception {
        // Проверки: отклонить пустое тело, неверную сигнатуру и файл выше лимита.
        request()
                .multiPart("file", "empty.pdf", new byte[0], "application/pdf")
                .postProcessors(jwt().authorities(new SimpleGrantedAuthority("ROLE_SYSTEM_ADMIN")))
                .when()
                .post("/api/admin/documents")
                .then()
                .statusCode(400);
        request()
                .multiPart("file", "not-a-pdf.pdf", new byte[3], "application/pdf")
                .postProcessors(jwt().authorities(new SimpleGrantedAuthority("ROLE_SYSTEM_ADMIN")))
                .when()
                .post("/api/admin/documents")
                .then()
                .statusCode(415);

        byte[] oversizedPdf =
                SizedPdfFixtures.pdfBytesOfSize(SizedPdfFixtures.MAX_UPLOAD_BYTES + 1);
        request()
                .multiPart("file", "oversized.pdf", oversizedPdf, "application/pdf")
                .postProcessors(jwt().authorities(new SimpleGrantedAuthority("ROLE_SYSTEM_ADMIN")))
                .when()
                .post("/api/admin/documents")
                .then()
                .statusCode(413)
                .body("code", equalTo("PAYLOAD_TOO_LARGE"))
                .body("message", equalTo("Размер PDF превышает 50 МБ"));

        assertThat(documentCount()).as("записи документов после отказа по типу и размеру").isZero();
        assertNoFiles();
    }

    /** Проверяет отмену загрузки при некорректном ответе анализатора. */
    @ParameterizedTest
    @ValueSource(strings = {"document", "ordinal", "dimension", "nan", "empty", "exception"})
    void invalidMlResponseCancelsUpload(String fault) throws Exception {
        // Подготовка: сформировать конкретный дефект ответа анализатора.
        when(analyzer.analyze(any(), any()))
                .thenAnswer(
                        invocation -> {
                            if (fault.equals("exception")) {
                                throw new IllegalStateException("ML unavailable");
                            }
                            UUID documentId = invocation.getArgument(0);
                            var chunk =
                                    new DocumentChunk(
                                            0,
                                            1,
                                            "Тест",
                                            fault.equals("nan")
                                                    ? List.of(Float.NaN, 0f, 0f, 0f, 0f, 0f, 0f, 0f)
                                                    : List.of(1f, 0f, 0f, 0f, 0f, 0f, 0f, 0f));
                            return new DocumentAnalysis(
                                    fault.equals("document") ? UUID.randomUUID() : documentId,
                                    1,
                                    true,
                                    "stub-v1",
                                    fault.equals("dimension") ? 3 : 8,
                                    fault.equals("empty")
                                            ? List.of()
                                            : fault.equals("ordinal")
                                                    ? List.of(chunk, chunk)
                                                    : List.of(chunk));
                        });
        // Действие и проверка: загрузка должна завершиться HTTP 502 без артефактов.
        request()
                .multiPart("file", "report.pdf", PDF, "application/pdf")
                .postProcessors(jwt().authorities(new SimpleGrantedAuthority("ROLE_SYSTEM_ADMIN")))
                .when()
                .post("/api/admin/documents")
                .then()
                .statusCode(502);
        assertThat(documentCount()).isZero();
        assertNoFiles();
    }

    /** Проверяет очистку файлов и чанков после частичной записи в Qdrant. */
    @Test
    void partialQdrantWriteDeletesChunksAndOriginalWithoutSavingMetadata() throws Exception {
        // Подготовка: записать чанк, затем смоделировать сбой Qdrant.
        doAnswer(
                        invocation -> {
                            invocation.callRealMethod();
                            throw new ResponseStatusException(
                                    HttpStatus.SERVICE_UNAVAILABLE, "Partial write");
                        })
                .when(vectors)
                .index(any(), any(), anyLong());
        // Действие: запустить компенсируемую загрузку.
        TestEvidence.input("PDF для загрузки", PDF);
        assertThatThrownBy(() -> TestEvidence.capture(() -> service.upload(pdf(), null, null)))
                .isInstanceOf(ResponseStatusException.class);
        var remainingPoints =
                qdrant.retrieveAsync(
                                STUB_COLLECTION,
                                id(DocumentVectorStore.chunkId(lastDocumentId.get(), 0)),
                                null)
                        .get();
        TestEvidence.output(
                "Состояние после компенсации",
                Map.of(
                        "documentCount", documentCount(),
                        "remainingChunkCount", remainingPoints.size(),
                        "remainingFilePaths", regularFilePaths()));
        assertThat(documentCount()).isZero();
        assertThat(remainingPoints).isEmpty();
        assertNoFiles();
    }

    /** Проверяет компенсационную очистку после ошибки фиксации метаданных. */
    @Test
    void metadataCommitFailureDeletesAlreadyIndexedChunksAndOriginal() throws Exception {
        // Подготовка: добавить отложенный триггер, который прерывает фиксацию.
        jdbc.execute(
                """
                CREATE FUNCTION fail_document_commit() RETURNS trigger LANGUAGE plpgsql AS $$
                BEGIN RAISE EXCEPTION 'simulated commit failure'; END $$
                """);
        jdbc.execute(
                """
                CREATE CONSTRAINT TRIGGER fail_document_commit AFTER INSERT ON documents
                DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION fail_document_commit()
                """);
        try {
            // Действие: дождаться отката метаданных и компенсационной очистки.
            TestEvidence.input("PDF для загрузки", PDF);
            assertThatThrownBy(() -> TestEvidence.capture(() -> service.upload(pdf(), null, null)))
                    .isInstanceOf(RuntimeException.class);
            var remainingPoints =
                    qdrant.retrieveAsync(
                                    STUB_COLLECTION,
                                    id(DocumentVectorStore.chunkId(lastDocumentId.get(), 0)),
                                    null)
                            .get();
            TestEvidence.output(
                    "Состояние после отката транзакции и компенсации",
                    Map.of(
                            "documentCount", documentCount(),
                            "remainingChunkCount", remainingPoints.size(),
                            "remainingFilePaths", regularFilePaths()));
            assertThat(documentCount()).isZero();
            assertThat(remainingPoints).isEmpty();
            assertNoFiles();
        } finally {
            jdbc.execute("DROP TRIGGER fail_document_commit ON documents");
            jdbc.execute("DROP FUNCTION fail_document_commit()");
        }
    }

    /** Проверяет независимость метаданных и векторов при параллельных загрузках. */
    @Test
    void concurrentUploadsKeepDocumentAndChunkIdsIndependent() throws Exception {
        // Подготовка и действие: одновременно выполнить четыре независимые загрузки.
        TestEvidence.input(
                "Параллельные загрузки",
                Map.of(
                        "count",
                        4,
                        "titles",
                        List.of("Report 0", "Report 1", "Report 2", "Report 3"),
                        "PDF",
                        PDF));
        var futures =
                java.util.stream.IntStream.range(0, 4)
                        .mapToObj(
                                i -> new java.util.concurrent.CompletableFuture<DocumentMetadata>())
                        .toList();
        for (int i = 0; i < futures.size(); i++) {
            int index = i;
            Thread.ofPlatform()
                    .start(
                            () -> {
                                try {
                                    futures.get(index)
                                            .complete(
                                                    service.upload(pdf(), "Report " + index, null));
                                } catch (Throwable failure) {
                                    futures.get(index).completeExceptionally(failure);
                                }
                            });
        }
        List<DocumentMetadata> uploadedDocuments = new ArrayList<>();
        for (var future : futures) {
            uploadedDocuments.add(future.get(30, TimeUnit.SECONDS));
        }
        List<DocumentMetadata> persistedDocuments = new ArrayList<>();
        List<List<RetrievedPoint>> indexedPointsByDocument = new ArrayList<>();
        List<Map<String, Object>> observedStates = new ArrayList<>();
        for (DocumentMetadata document : uploadedDocuments) {
            var persisted = service.get(document.documentId());
            var indexedPoints =
                    qdrant.retrieveAsync(
                                    STUB_COLLECTION,
                                    id(DocumentVectorStore.chunkId(document.documentId(), 0)),
                                    true,
                                    true,
                                    null)
                            .get();
            persistedDocuments.add(persisted);
            indexedPointsByDocument.add(indexedPoints);
            observedStates.add(
                    Map.of(
                            "documentId", document.documentId(),
                            "uploadedTitle", document.title(),
                            "persistedTitle", persisted.title(),
                            "persistedChunkCount", persisted.chunkCount(),
                            "indexedPoints",
                                    indexedPoints.stream()
                                            .map(this::retrievedPointEvidence)
                                            .toList()));
        }
        TestEvidence.output(
                "Результаты параллельных загрузок",
                Map.of(
                        "uploadedMetadata",
                        uploadedDocuments,
                        "documentCount",
                        documentCount(),
                        "storageFilePaths",
                        regularFilePaths(),
                        "persistedAndIndexedStates",
                        observedStates));

        // Проверки: у каждой загрузки свои метаданные и точка вектора.
        for (int index = 0; index < uploadedDocuments.size(); index++) {
            DocumentMetadata document = uploadedDocuments.get(index);
            assertThat(persistedDocuments.get(index).title()).isEqualTo(document.title());
            assertThat(indexedPointsByDocument.get(index)).hasSize(1);
        }
        assertThat(documentCount()).isEqualTo(4);
    }

    /** Создаёт request-local спецификацию для существующего Spring MockMvc. */
    private MockMvcRequestSpecification request() {
        return MockMvcRestAssured.request(mvc);
    }

    /** Возвращает число сохранённых документов. */
    private long documentCount() {
        return jdbc.queryForObject("SELECT count(*) FROM documents", Long.class);
    }

    /** Проверяет, что после неуспешной загрузки в хранилище не осталось файлов. */
    private void assertNoFiles() throws Exception {
        try (var files = Files.walk(STORAGE)) {
            assertThat(files.filter(Files::isRegularFile).toList()).isEmpty();
        }
    }

    /** Возвращает относительные пути файлов хранилища для evidence. */
    private List<String> regularFilePaths() throws Exception {
        try (var files = Files.walk(STORAGE)) {
            return files.filter(Files::isRegularFile)
                    .map(STORAGE::relativize)
                    .map(Path::toString)
                    .toList();
        }
    }

    /** Собирает фактические поля найденной точки Qdrant для evidence. */
    private Map<String, Object> retrievedPointEvidence(RetrievedPoint point) {
        var payload = point.getPayloadMap();
        var vector = point.getVectors().getVector().getDense();
        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("pointId", point.getId().getUuid());
        evidence.put("documentId", payloadValue(payload, "documentId"));
        evidence.put("text", payloadValue(payload, "text"));
        evidence.put("page", payloadValue(payload, "page"));
        evidence.put("ordinal", payloadValue(payload, "ordinal"));
        evidence.put("stub", payloadValue(payload, "stub"));
        evidence.put("embeddingModel", payloadValue(payload, "embeddingModel"));
        evidence.put("materialType", payloadValue(payload, "materialType"));
        evidence.put("accessScope", payloadValue(payload, "accessScope"));
        evidence.put("publicationDate", payloadValue(payload, "publicationDate"));
        evidence.put("vectorDimension", vector.getDataCount());
        evidence.put("vector", vector.getDataList());
        return evidence;
    }

    /** Преобразует значение payload Qdrant в компактное представление. */
    private Object payloadValue(Map<String, Value> payload, String fieldName) {
        Value value = payload.get(fieldName);
        if (value == null) return null;
        return switch (value.getKindCase()) {
            case NULL_VALUE -> null;
            case DOUBLE_VALUE -> value.getDoubleValue();
            case INTEGER_VALUE -> value.getIntegerValue();
            case STRING_VALUE -> value.getStringValue();
            case BOOL_VALUE -> value.getBoolValue();
            case STRUCT_VALUE, LIST_VALUE ->
                    Map.of("kind", value.getKindCase().name(), "actualValue", value.toString());
            case KIND_NOT_SET -> "KIND_NOT_SET";
        };
    }

    /** Создаёт тестовую multipart-часть с PDF. */
    private static MockMultipartFile pdf() {
        return new MockMultipartFile("file", "report.pdf", "application/pdf", PDF);
    }

    /** Создаёт изолированную временную директорию для файлов документов. */
    private static Path createStorage() {
        try {
            return Files.createTempDirectory("knowledge-documents-test-");
        } catch (IOException exception) {
            throw new java.io.UncheckedIOException(exception);
        }
    }
}
