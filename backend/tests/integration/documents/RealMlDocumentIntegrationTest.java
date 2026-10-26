package alpha.business.insight.core.qa.documents.integration;

import static alpha.business.insight.core.service.DocumentVectorStore.STUB_COLLECTION;
import static alpha.business.insight.core.service.DocumentVectorStore.chunkId;
import static io.qdrant.client.ConditionFactory.matchKeyword;
import static io.qdrant.client.PointIdFactory.id;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import alpha.business.insight.core.qa.helpers.SizedPdfFixtures;
import alpha.business.insight.core.qa.helpers.SyntheticPdf;
import alpha.business.insight.core.qa.helpers.TestEvidence;
import alpha.business.insight.core.qa.support.RealStackE2eTest;
import alpha.business.insight.core.service.DocumentStorage;
import io.qameta.allure.Allure;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import io.qameta.allure.Story;
import io.qdrant.client.QdrantClient;
import io.qdrant.client.grpc.Common.Filter;
import io.qdrant.client.grpc.JsonWithInt.Value;
import io.qdrant.client.grpc.Points.RetrievedPoint;
import io.qdrant.client.grpc.Points.UpdateStatus;
import io.restassured.RestAssured;
import io.restassured.http.ContentType;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.ImageFromDockerfile;

/** Проверяет загрузку документа через настоящий Python ML-сервис и реальные хранилища. */
@Epic("Backend")
@Feature("Документы аналитики банка")
@Story("Реальная обработка PDF Python ML-сервисом")
@Tag("integration")
@Tag("documents")
@Tag("ml")
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"ml.stub=false", "spring.ai.model.chat=none"})
class RealMlDocumentIntegrationTest extends RealStackE2eTest {
    private static final GenericContainer<?> PYTHON_ML =
            new GenericContainer<>(
                            new ImageFromDockerfile("alfa-business-insights-real-ml-test", false)
                                    .withFileFromPath(".", mlDirectory()))
                    .withExposedPorts(8000)
                    .withEnv("ML_EMBEDDER", "stub")
                    .withEnv("ML_STUB", "false")
                    .waitingFor(
                            Wait.forHttp("/health")
                                    .forPort(8000)
                                    .forStatusCode(200)
                                    .withStartupTimeout(Duration.ofMinutes(3)));

    @Autowired private QdrantClient qdrant;

    @Autowired private DocumentStorage storage;

    @org.springframework.beans.factory.annotation.Value("${documents.storage-path}")
    private String storagePath;

    private UUID documentId;

    private final List<UUID> documentIdsForCleanup = new ArrayList<>();

    static {
        PYTHON_ML.start();
    }

    /** Передаёт URL собранного Python-образа Spring-клиенту Feign. */
    @DynamicPropertySource
    static void realMlUrl(DynamicPropertyRegistry properties) {
        properties.add(
                "ml.url",
                () -> "http://" + PYTHON_ML.getHost() + ":" + PYTHON_ML.getMappedPort(8000));
    }

    /** Проверяет загрузку PDF, индексацию чанков и скачивание оригинала через живые сервисы. */
    @Test
    void TestIntegrationUploadPdfThroughRealPython() throws Exception {
        // Подготовка: создать две страницы с текстом для настоящего Python-парсера.
        List<String> pageTexts =
                List.of("Revenue increased to 120 dollars.", "Operating costs were 45 dollars.");
        byte[] pdf = SyntheticPdf.textDocument(pageTexts);
        var upload =
                new SyntheticPdf.MultipartUpload(
                        "quarterly-report.pdf",
                        "application/pdf",
                        pdf,
                        "2026-10-24",
                        "two-page PDF with real extractable text for the Python ML service");

        // Действие и проверки выполняются через живые HTTP-сервисы и реальные хранилища.
        Allure.step(
                "Загрузить многостраничный PDF и проверить реальную обработку",
                () -> {
                    String token =
                            Allure.step(
                                    "Получить JWT системного администратора",
                                    () -> keycloak.accessToken("sysadmin", "sysadmin-dev"));
                    var response =
                            TestEvidence.step(
                                    "Загрузить PDF через backend и Python ML",
                                    List.of(
                                            new TestEvidence.Input("PDF", pdf),
                                            new TestEvidence.Input("Имя файла", upload.filename()),
                                            new TestEvidence.Input("Текст страниц", pageTexts)),
                                    () -> {
                                        var actual =
                                                backend.postMultipart(
                                                        "/admin/documents", upload, token);
                                        recordDocumentIdForCleanup(actual);
                                        return actual;
                                    },
                                    actual -> {
                                        TestEvidence.output(
                                                "Фактический HTTP-ответ загрузки",
                                                Map.of(
                                                        "status", actual.statusCode(),
                                                        "location",
                                                                String.valueOf(
                                                                        actual.getHeader(
                                                                                HttpHeaders
                                                                                        .LOCATION)),
                                                        "body", actual.asString()));
                                        assertThat(actual.statusCode()).isEqualTo(201);
                                        var metadata = json(actual);
                                        assertThat(documentId).isNotNull();
                                        assertThat(metadata.path("title").asText())
                                                .isEqualTo("E2E synthetic report");
                                        assertThat(metadata.path("fileName").asText())
                                                .isEqualTo("quarterly-report.pdf");
                                        assertThat(metadata.path("sizeBytes").asLong())
                                                .isEqualTo(pdf.length);
                                        assertThat(metadata.path("stub").asBoolean()).isTrue();
                                        assertThat(metadata.path("embeddingModel").asText())
                                                .isEqualTo("stub-v1");
                                        assertThat(metadata.path("embeddingDimension").asInt())
                                                .isEqualTo(8);
                                        assertThat(metadata.path("chunkCount").asInt())
                                                .isEqualTo(pageTexts.size());
                                        assertThat(metadata.path("schemaVersion").asInt())
                                                .isEqualTo(1);
                                        assertThat(metadata.path("sha256").asText()).hasSize(64);
                                        assertThat(actual.getHeader(HttpHeaders.LOCATION))
                                                .contains(documentId.toString());
                                        assertThat(
                                                        jdbc.queryForObject(
                                                                "SELECT COUNT(*) FROM documents WHERE id = ?",
                                                                Long.class,
                                                                documentId))
                                                .isEqualTo(1L);
                                    });

                    verifyPersistedMetadata(documentId, token, response);
                    verifyIndexedPages(documentId, pageTexts);
                    verifyDownloadedOriginal(documentId, token, pdf);
                });
    }

    /** Проверяет OCR скана через backend и повторную загрузку с теми же байтами PDF. */
    @Test
    void TestIntegrationUploadMixedTextAndScannedPdfThroughRealPython() throws Exception {
        Path fixture =
                mlDirectory()
                        .getParent()
                        .resolve("backend/tests/fixtures/pdf/ocr-mixed-text-and-scan.pdf");
        byte[] pdf = Files.readAllBytes(fixture);
        String expectedSha256 = sha256(pdf);
        List<SyntheticPdf.MultipartUpload> uploads =
                List.of(
                        new SyntheticPdf.MultipartUpload(
                                "ocr-mixed-text-and-scan-first.pdf",
                                "application/pdf",
                                pdf,
                                "2026-10-26",
                                "Синтетический текстовый лист и отсканированный лист на английском"),
                        new SyntheticPdf.MultipartUpload(
                                "ocr-mixed-text-and-scan-repeat.pdf",
                                "application/pdf",
                                pdf,
                                "2026-10-26",
                                "Повторная загрузка тех же байтов для проверки OCR cache"));

        Allure.step(
                "Индексировать текстовую страницу и скан через backend и реальный Python ML",
                () -> {
                    String token =
                            Allure.step(
                                    "Получить JWT системного администратора",
                                    () -> keycloak.accessToken("sysadmin", "sysadmin-dev"));
                    List<UUID> uploadedIds = new ArrayList<>();
                    for (SyntheticPdf.MultipartUpload upload : uploads) {
                        var response =
                                TestEvidence.step(
                                        "Загрузить " + upload.filename(),
                                        List.of(
                                                new TestEvidence.Input("PDF", pdf),
                                                new TestEvidence.Input(
                                                        "SHA-256 одинаковых байтов",
                                                        expectedSha256),
                                                new TestEvidence.Input(
                                                        "Имя файла", upload.filename())),
                                        () -> {
                                            var actual =
                                                    backend.postMultipart(
                                                            "/admin/documents", upload, token);
                                            recordDocumentIdForCleanup(actual);
                                            return actual;
                                        },
                                        actual -> {
                                            TestEvidence.output(
                                                    "Фактический ответ загрузки",
                                                    Map.of(
                                                            "status",
                                                            actual.statusCode(),
                                                            "body",
                                                            actual.asString()));
                                            assertThat(actual.statusCode()).isEqualTo(201);
                                            var metadata = json(actual);
                                            assertThat(documentId).isNotNull();
                                            assertThat(metadata.path("fileName").asText())
                                                    .isEqualTo(upload.filename());
                                            assertThat(metadata.path("sizeBytes").asLong())
                                                    .isEqualTo(pdf.length);
                                            assertThat(metadata.path("sha256").asText())
                                                    .isEqualTo(expectedSha256);
                                            assertThat(metadata.path("stub").asBoolean()).isTrue();
                                            assertThat(metadata.path("embeddingModel").asText())
                                                    .isEqualTo("stub-v1");
                                            assertThat(metadata.path("embeddingDimension").asInt())
                                                    .isEqualTo(8);
                                            assertThat(metadata.path("chunkCount").asInt())
                                                    .isEqualTo(2);
                                            assertThat(actual.getHeader(HttpHeaders.LOCATION))
                                                    .contains(documentId.toString());
                                            assertThat(
                                                            jdbc.queryForObject(
                                                                    "SELECT COUNT(*) FROM documents WHERE id = ?",
                                                                    Long.class,
                                                                    documentId))
                                                    .isEqualTo(1L);
                                        });

                        UUID uploadedId = documentId;
                        uploadedIds.add(uploadedId);
                        assertThat(uploadedIds).doesNotHaveDuplicates();
                        verifyPersistedMetadata(uploadedId, token, response);
                        verifyOcrPages(uploadedId);
                        verifyDownloadedOriginal(uploadedId, token, pdf);
                        assertThat(
                                        jdbc.queryForObject(
                                                "SELECT sha256 FROM documents WHERE id = ?",
                                                String.class,
                                                uploadedId))
                                .isEqualTo(expectedSha256);
                    }

                    assertThat(uploadedIds).hasSize(2).doesNotHaveDuplicates();
                    TestEvidence.step(
                            "Проверить, что Python создал OCR cache для исходного PDF",
                            List.of(
                                    new TestEvidence.Input("SHA-256 PDF", expectedSha256),
                                    new TestEvidence.Input(
                                            "Документы с одинаковыми байтами", uploadedIds)),
                            () ->
                                    PYTHON_ML.execInContainer(
                                            "sh",
                                            "-c",
                                            "test -s /tmp/ml-ocr-cache/"
                                                    + expectedSha256
                                                    + ".json"),
                            actual -> assertThat(actual.getExitCode()).isZero());
                });
    }

    /** Проверяет русский текстовый слой и ключевые слова OCR на физической странице 2. */
    private void verifyOcrPages(UUID uploadedId) throws Exception {
        List<String> pageTexts = new ArrayList<>();
        for (int ordinal = 0; ordinal < 2; ordinal++) {
            int expectedOrdinal = ordinal;
            var points =
                    qdrant.retrieveAsync(
                                    STUB_COLLECTION,
                                    id(chunkId(uploadedId, expectedOrdinal)),
                                    true,
                                    true,
                                    null)
                            .get(10, TimeUnit.SECONDS);
            assertThat(points)
                    .as("Один Qdrant point для документа %s, ordinal %s", uploadedId, ordinal)
                    .hasSize(1);
            Map<String, Value> payload = points.getFirst().getPayloadMap();
            TestEvidence.output("Фактический OCR payload Qdrant", payloadEvidence(payload));
            assertThat(payload.get("documentId").getStringValue()).isEqualTo(uploadedId.toString());
            assertThat(payload.get("page").getIntegerValue()).isEqualTo(ordinal + 1L);
            assertThat(payload.get("ordinal").getIntegerValue()).isEqualTo(ordinal);
            assertThat(payload.get("embeddingModel").getStringValue()).isEqualTo("stub-v1");
            assertThat(payload.get("stub").getBoolValue()).isTrue();
            var vector = points.getFirst().getVectors().getVector().getDense();
            List<Float> vectorValues = vector.getDataList();
            TestEvidence.output("Вектор страницы из Qdrant", vectorValues);
            assertThat(vector.getDataCount()).isEqualTo(8);
            assertThat(vectorValues)
                    .allSatisfy(value -> assertThat(Float.isFinite(value)).isTrue());
            double normSquared =
                    vectorValues.stream().mapToDouble(value -> (double) value * value).sum();
            assertThat(normSquared).isCloseTo(1.0, within(0.00001));
            assertThat(vectorValues.get(1) / vectorValues.get(0)).isCloseTo(0.5f, within(0.0001f));
            assertThat(vectorValues.get(2) / vectorValues.get(0)).isCloseTo(0.25f, within(0.0001f));
            assertThat(vectorValues.get(3) / vectorValues.get(0))
                    .isCloseTo(0.125f, within(0.0001f));
            assertThat(vectorValues.subList(4, 8)).containsOnly(0.0f);
            pageTexts.add(payload.get("text").getStringValue());
        }

        String textPage = normalizePageText(pageTexts.getFirst());
        String scannedPage = normalizePageText(pageTexts.get(1));
        assertThat(textPage)
                .contains("синтетический отчёт", "120 миллионов рублей", "стабильный рост бизнеса");
        assertThat(scannedPage)
                .contains("scanned page two", "120 million rubles", "45 million rubles", "7314");
    }

    private static String normalizePageText(String text) {
        return text.replace('\u00a0', ' ').replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }

    /** Проверяет отказ реального Python ML для PDF с паролем и отсутствие побочных эффектов. */
    @Test
    void TestIntegrationRejectPasswordProtectedPdfWithoutArtifacts() throws Exception {
        Path fixture =
                mlDirectory()
                        .getParent()
                        .resolve("backend/tests/fixtures/pdf/ocr-password-protected.pdf");
        byte[] pdf = Files.readAllBytes(fixture);
        String fileName = "ocr-password-protected.pdf";
        var upload =
                new SyntheticPdf.MultipartUpload(
                        fileName,
                        "application/pdf",
                        pdf,
                        "2026-10-26",
                        "Синтетический PDF зашифрован паролем для проверки отказа OCR pipeline");
        Map<String, Long> storageBefore = snapshotStoredFiles();
        long documentRowsBefore =
                jdbc.queryForObject(
                        "SELECT COUNT(*) FROM documents WHERE file_name = ?", Long.class, fileName);
        long qdrantPointsBefore = snapshotStubPointCount();

        Allure.step(
                "Отклонить защищённый паролем PDF через backend и реальный Python ML",
                () -> {
                    String token =
                            Allure.step(
                                    "Получить JWT системного администратора",
                                    () -> keycloak.accessToken("sysadmin", "sysadmin-dev"));
                    var response =
                            TestEvidence.step(
                                    "Отправить зашифрованный PDF",
                                    List.of(
                                            new TestEvidence.Input("PDF", pdf),
                                            new TestEvidence.Input("Имя файла", fileName)),
                                    () -> backend.postMultipart("/admin/documents", upload, token),
                                    actual ->
                                            TestEvidence.output(
                                                    "Фактический ответ отказа ML",
                                                    Map.of(
                                                            "status",
                                                            actual.statusCode(),
                                                            "body",
                                                            actual.asString())));

                    assertThat(response.statusCode()).isEqualTo(422);
                    assertThat(response.jsonPath().getBoolean("retryable")).isFalse();
                    assertThat(response.jsonPath().getString("message")).contains("извлечь текст");

                    TestEvidence.step(
                            "Проверить отсутствие документов, файлов и точек после отказа",
                            List.of(
                                    new TestEvidence.Input("Имя файла", fileName),
                                    new TestEvidence.Input(
                                            "Строки PostgreSQL до запроса", documentRowsBefore),
                                    new TestEvidence.Input(
                                            "Qdrant points до запроса", qdrantPointsBefore),
                                    new TestEvidence.Input("Файлы до запроса", storageBefore)),
                            () ->
                                    Map.of(
                                            "documentRows",
                                            jdbc.queryForObject(
                                                    "SELECT COUNT(*) FROM documents WHERE file_name = ?",
                                                    Long.class,
                                                    fileName),
                                            "qdrantPoints",
                                            snapshotStubPointCount(),
                                            "storageFiles",
                                            snapshotStoredFiles()),
                            actual -> {
                                assertThat(actual.get("documentRows"))
                                        .isEqualTo(documentRowsBefore);
                                assertThat(actual.get("qdrantPoints"))
                                        .isEqualTo(qdrantPointsBefore);
                                assertThat(actual.get("storageFiles")).isEqualTo(storageBefore);
                            });
                });
    }

    /** Возвращает число точек в stub-коллекции, сохраняя отсутствие коллекции как ноль. */
    private long snapshotStubPointCount() throws Exception {
        if (!qdrant.collectionExistsAsync(STUB_COLLECTION, Duration.ofSeconds(10))
                .get(10, TimeUnit.SECONDS)) {
            return 0;
        }
        return RestAssured.given()
                .contentType(ContentType.JSON)
                .body(Map.of("exact", true))
                .post(
                        "http://"
                                + QDRANT.getHost()
                                + ":"
                                + QDRANT.getMappedPort(6333)
                                + "/collections/"
                                + STUB_COLLECTION
                                + "/points/count")
                .then()
                .statusCode(200)
                .extract()
                .jsonPath()
                .getLong("result.count");
    }

    /** Проверяет обработку опубликованного отчёта ЦБ, извлечённый текст и исходный PDF. */
    @Test
    void TestIntegrationIndexPublishedBankReport() throws Exception {
        // Подготовка: использовать исходный публичный отчёт и его известную контрольную сумму.
        byte[] pdf =
                Files.readAllBytes(
                        mlDirectory()
                                .resolve(
                                        "tests/fixtures/pdf/cbr-inflation-expectations-2024-01.pdf"));
        String expectedSha256 = "ad659e26473e98f7eb1994cb35c0b3df13fc4d7732dc8fd61b5eeddd200e0a02";
        var upload =
                new SyntheticPdf.MultipartUpload(
                        "cbr-inflation-expectations-2024-01.pdf",
                        "application/pdf",
                        pdf,
                        "2024-01-31",
                        "Опубликованный отчёт Банка России об инфляционных ожиданиях, 11 страниц");

        // Действие и проверки: загрузить отчёт, проверить чанки и скачать оригинал.
        Allure.step(
                "Индексировать опубликованный отчёт Банка России",
                () -> {
                    String token =
                            Allure.step(
                                    "Получить JWT системного администратора",
                                    () -> keycloak.accessToken("sysadmin", "sysadmin-dev"));
                    var response =
                            TestEvidence.step(
                                    "Загрузить реальный PDF отчёта ЦБ через Python ML",
                                    List.of(
                                            new TestEvidence.Input("Файл отчёта", pdf),
                                            new TestEvidence.Input(
                                                    "Ожидаемый SHA-256", expectedSha256)),
                                    () -> {
                                        var actual =
                                                backend.postMultipart(
                                                        "/admin/documents", upload, token);
                                        recordDocumentIdForCleanup(actual);
                                        return actual;
                                    },
                                    actual -> {
                                        TestEvidence.output(
                                                "Фактический ответ загрузки отчёта",
                                                Map.of(
                                                        "status",
                                                        actual.statusCode(),
                                                        "body",
                                                        actual.asString()));
                                        assertThat(actual.statusCode()).isEqualTo(201);
                                        var metadata = json(actual);
                                        assertThat(documentId).isNotNull();
                                        assertThat(metadata.path("fileName").asText())
                                                .isEqualTo(upload.filename());
                                        assertThat(metadata.path("sizeBytes").asLong())
                                                .isEqualTo(pdf.length);
                                        assertThat(metadata.path("publicationDate").asText())
                                                .isEqualTo("2024-01-31");
                                        assertThat(metadata.path("sha256").asText())
                                                .isEqualTo(expectedSha256);
                                        assertThat(metadata.path("stub").asBoolean()).isTrue();
                                        assertThat(metadata.path("embeddingModel").asText())
                                                .isEqualTo("stub-v1");
                                        assertThat(metadata.path("embeddingDimension").asInt())
                                                .isEqualTo(8);
                                        assertThat(metadata.path("chunkCount").asInt())
                                                .isPositive();
                                        assertThat(metadata.path("schemaVersion").asInt())
                                                .isEqualTo(1);
                                        assertThat(actual.getHeader(HttpHeaders.LOCATION))
                                                .contains(documentId.toString());
                                        assertThat(
                                                        jdbc.queryForObject(
                                                                "SELECT COUNT(*) FROM documents WHERE id = ?",
                                                                Long.class,
                                                                documentId))
                                                .isEqualTo(1L);
                                    });

                    var metadata = json(response);
                    verifyPersistedMetadata(documentId, token, response);
                    verifyCbrChunks(documentId, metadata.path("chunkCount").asInt());
                    verifyCbrOriginal(documentId, token, pdf, expectedSha256);
                });
    }

    /** Проверяет HTTP 422 от Python ML для PDF без текста и очистку данных. */
    @Test
    void TestIntegrationRejectTextlessPdfWithoutArtifacts() throws Exception {
        // Подготовка: создать корректный PDF с физической страницей без текста.
        byte[] pdf = SyntheticPdf.textDocument(List.of(""));
        var upload =
                new SyntheticPdf.MultipartUpload(
                        "blank-page.pdf",
                        "application/pdf",
                        pdf,
                        "2026-10-24",
                        "valid PDF signature with a physical page but no extractable text");
        Map<String, Long> storageBefore = snapshotStoredFiles();
        long documentRowsBefore =
                jdbc.queryForObject(
                        "SELECT COUNT(*) FROM documents WHERE file_name = ?",
                        Long.class,
                        upload.filename());

        // Действие и проверки: получить отказ ML и сверить компенсационную очистку.
        Allure.step(
                "Отклонить PDF без текста и проверить компенсацию загрузки",
                () -> {
                    String token =
                            Allure.step(
                                    "Получить JWT системного администратора",
                                    () -> keycloak.accessToken("sysadmin", "sysadmin-dev"));
                    var response =
                            TestEvidence.step(
                                    "Отправить PDF без извлекаемого текста",
                                    List.of(
                                            new TestEvidence.Input("PDF", pdf),
                                            new TestEvidence.Input("Имя файла", upload.filename())),
                                    () -> backend.postMultipart("/admin/documents", upload, token),
                                    actual ->
                                            TestEvidence.output(
                                                    "Фактический ответ backend после отказа Python",
                                                    Map.of(
                                                            "status",
                                                            actual.statusCode(),
                                                            "body",
                                                            actual.asString())));

                    TestEvidence.step(
                            "Проверить отсутствие артефактов неудачной загрузки",
                            List.of(
                                    new TestEvidence.Input("Имя файла", upload.filename()),
                                    new TestEvidence.Input(
                                            "Файлы до запроса: относительный путь и размер",
                                            storageBefore),
                                    new TestEvidence.Input(
                                            "Строки PostgreSQL до запроса", documentRowsBefore)),
                            () ->
                                    Map.of(
                                            "documentRows",
                                            jdbc.queryForObject(
                                                    "SELECT COUNT(*) FROM documents WHERE file_name = ?",
                                                    Long.class,
                                                    upload.filename()),
                                            "storageFiles",
                                            snapshotStoredFiles()),
                            actual -> {
                                assertThat(actual.get("documentRows"))
                                        .as("Число строк для %s", upload.filename())
                                        .isEqualTo(documentRowsBefore);
                                assertThat(actual.get("storageFiles"))
                                        .as("Снимок файлового хранилища после отказа")
                                        .isEqualTo(storageBefore);
                            });

                    Allure.step(
                            "Проверить опубликованный HTTP-контракт отказа ML",
                            () -> {
                                assertThat(response.statusCode())
                                        .as("Пустой PDF должен завершиться HTTP 422")
                                        .isEqualTo(422);
                                assertThat(response.jsonPath().getBoolean("retryable"))
                                        .as(
                                                "Отказ из-за отсутствия текста не должен быть повторяемым")
                                        .isFalse();
                                assertThat(response.jsonPath().getString("message"))
                                        .as("Сообщение должно объяснять отказ для пустого PDF")
                                        .contains("извлечь текст");
                            });
                });
    }

    /** Проверяет реальные границы multipart-загрузки рядом с лимитом 50 MiB. */
    @ParameterizedTest(name = "{0}")
    @MethodSource("pdfUploadSizeCases")
    void TestIntegrationPdfUploadSizeBoundaries(
            String caseName, long targetBytes, int expectedStatus) throws Exception {
        // Подготовка: сформировать валидный PDF точного размера и снять исходное состояние.
        byte[] pdf = pdfForSize(targetBytes);
        String fileName = "upload-size-" + targetBytes + ".pdf";
        var upload =
                new SyntheticPdf.MultipartUpload(
                        fileName,
                        "application/pdf",
                        pdf,
                        "2024-01-31",
                        "Проверка размера PDF: " + targetBytes + " байт");
        String expectedSha256 = sha256(pdf);
        long initialDocumentRows =
                jdbc.queryForObject(
                        "SELECT COUNT(*) FROM documents WHERE file_name = ?", Long.class, fileName);
        Map<String, Long> storageBefore = snapshotStoredFiles();

        // Действие и проверки: выполнить multipart-запрос и сверить сохранение либо отказ.
        Allure.step(
                "Загрузить PDF размером " + targetBytes + " байт (" + caseName + ")",
                () -> {
                    String token =
                            Allure.step(
                                    "Получить JWT системного администратора",
                                    () -> keycloak.accessToken("sysadmin", "sysadmin-dev"));
                    var response =
                            TestEvidence.step(
                                    "Отправить PDF заданного размера в backend и реальный Python ML",
                                    List.of(
                                            new TestEvidence.Input("Сценарий", caseName),
                                            new TestEvidence.Input(
                                                    "Размер файла в байтах", targetBytes),
                                            new TestEvidence.Input(
                                                    "Ожидаемый SHA-256", expectedSha256)),
                                    () -> {
                                        var actual =
                                                backend.postMultipart(
                                                        "/admin/documents", upload, token);
                                        recordDocumentIdForCleanup(actual);
                                        return actual;
                                    },
                                    actual ->
                                            TestEvidence.output(
                                                    "Фактический ответ загрузки",
                                                    Map.of(
                                                            "status",
                                                            actual.statusCode(),
                                                            "body",
                                                            actual.asString(),
                                                            "expectedStatus",
                                                            expectedStatus)));

                    assertThat(response.statusCode())
                            .as("HTTP-статус для %s, фактически: %s", caseName, response.asString())
                            .isEqualTo(expectedStatus);
                    if (expectedStatus == 201) {
                        var metadata = json(response);
                        TestEvidence.step(
                                "Проверить метаданные, строку PostgreSQL и сохранённый оригинал",
                                List.of(
                                        new TestEvidence.Input("documentId", documentId),
                                        new TestEvidence.Input("Размер исходного PDF", targetBytes),
                                        new TestEvidence.Input(
                                                "Ожидаемый SHA-256", expectedSha256)),
                                () -> {
                                    Map<String, Object> persisted =
                                            Map.of(
                                                    "sizeBytes",
                                                            jdbc.queryForObject(
                                                                    "SELECT size_bytes FROM documents WHERE id = ?",
                                                                    Long.class,
                                                                    documentId),
                                                    "sha256",
                                                            jdbc.queryForObject(
                                                                    "SELECT sha256 FROM documents WHERE id = ?",
                                                                    String.class,
                                                                    documentId),
                                                    "fileSize",
                                                            Files.size(
                                                                    Path.of(storagePath)
                                                                            .resolve(
                                                                                    storage
                                                                                            .relativePath(
                                                                                                    documentId))),
                                                    "fileSha256",
                                                            sha256(
                                                                    Files.readAllBytes(
                                                                            Path.of(storagePath)
                                                                                    .resolve(
                                                                                            storage
                                                                                                    .relativePath(
                                                                                                            documentId)))));
                                    return persisted;
                                },
                                actual -> {
                                    assertThat(metadata.path("sizeBytes").asLong())
                                            .as("API sizeBytes for %s", caseName)
                                            .isEqualTo(targetBytes);
                                    assertThat(metadata.path("sha256").asText())
                                            .as("API SHA-256 for %s", caseName)
                                            .isEqualTo(expectedSha256);
                                    assertThat(metadata.path("publicationDate").asText())
                                            .as(
                                                    "Дата публикации исходного отчёта ЦБ для %s",
                                                    caseName)
                                            .isEqualTo("2024-01-31");
                                    assertThat(actual.get("sizeBytes"))
                                            .as("PostgreSQL size_bytes for %s", caseName)
                                            .isEqualTo(targetBytes);
                                    assertThat(actual.get("sha256"))
                                            .as("PostgreSQL sha256 for %s", caseName)
                                            .isEqualTo(expectedSha256);
                                    assertThat(actual.get("fileSize"))
                                            .as("Размер оригинала на диске для %s", caseName)
                                            .isEqualTo(targetBytes);
                                    assertThat(actual.get("fileSha256"))
                                            .as("SHA-256 оригинала на диске для %s", caseName)
                                            .isEqualTo(expectedSha256);
                                });
                        assertThat(
                                        jdbc.queryForObject(
                                                "SELECT COUNT(*) FROM documents WHERE id = ?",
                                                Long.class,
                                                documentId))
                                .as("Одна строка PostgreSQL для %s", caseName)
                                .isEqualTo(1L);
                        if (targetBytes == SizedPdfFixtures.MAX_UPLOAD_BYTES) {
                            TestEvidence.step(
                                    "Скачать PDF на точном лимите и сверить оригинал",
                                    List.of(
                                            new TestEvidence.Input("documentId", documentId),
                                            new TestEvidence.Input(
                                                    "Ожидаемый SHA-256", expectedSha256)),
                                    () ->
                                            backend.getBytes(
                                                    "/documents/" + documentId + "/file", token),
                                    actual -> {
                                        assertThat(actual.statusCode())
                                                .as(
                                                        "Скачивание оригинала на лимите для %s",
                                                        caseName)
                                                .isEqualTo(200);
                                        assertThat(actual.asByteArray())
                                                .as("Размер скачанного PDF для %s", caseName)
                                                .hasSize((int) targetBytes);
                                        assertThat(sha256(actual.asByteArray()))
                                                .as("SHA-256 скачанного PDF для %s", caseName)
                                                .isEqualTo(expectedSha256);
                                    });
                        }
                    } else {
                        assertThat(response.jsonPath().getString("code"))
                                .as(
                                        "ApiError.code для %s; HTTP %s, тело: %s",
                                        caseName, response.statusCode(), response.asString())
                                .isEqualTo("PAYLOAD_TOO_LARGE");
                        assertThat(response.jsonPath().getBoolean("retryable"))
                                .as("ApiError.retryable для %s", caseName)
                                .isFalse();
                        TestEvidence.step(
                                "Проверить отсутствие побочных эффектов и доступность backend",
                                List.of(
                                        new TestEvidence.Input("Имя файла", fileName),
                                        new TestEvidence.Input(
                                                "Число строк до загрузки", initialDocumentRows),
                                        new TestEvidence.Input(
                                                "Файлы до запроса: относительный путь и размер",
                                                storageBefore)),
                                () -> {
                                    long rowsAfter =
                                            jdbc.queryForObject(
                                                    "SELECT COUNT(*) FROM documents WHERE file_name = ?",
                                                    Long.class,
                                                    fileName);
                                    Map<String, Long> storageAfter = snapshotStoredFiles();
                                    var apiDocs = backend.get("/v3/api-docs", token);
                                    return Map.of(
                                            "documentRowsAfter",
                                            rowsAfter,
                                            "storageFilesAfter",
                                            storageAfter,
                                            "apiDocsStatus",
                                            apiDocs.statusCode());
                                },
                                actual -> {
                                    assertThat(actual.get("documentRowsAfter"))
                                            .as(
                                                    "Число строк PostgreSQL не меняется для %s",
                                                    caseName)
                                            .isEqualTo(initialDocumentRows);
                                    assertThat(actual.get("storageFilesAfter"))
                                            .as(
                                                    "Пути и размеры файлов хранилища не меняются для %s",
                                                    caseName)
                                            .isEqualTo(storageBefore);
                                    assertThat(actual.get("apiDocsStatus"))
                                            .as(
                                                    "GET /api/v3/api-docs после отклонения %s",
                                                    caseName)
                                            .isEqualTo(200);
                                });
                    }
                });
    }

    /** Задаёт приёмлемые и превышающие лимит размеры в байтах. */
    private static Stream<Arguments> pdfUploadSizeCases() {
        long limit = SizedPdfFixtures.MAX_UPLOAD_BYTES;
        return Stream.of(
                Arguments.of("36 MiB", 36 * SizedPdfFixtures.MIB, 201),
                Arguments.of("50 MiB минус 1 байт", limit - 1, 201),
                Arguments.of("ровно 50 MiB", limit, 201),
                Arguments.of("50 MiB плюс 1 байт", limit + 1, 413),
                Arguments.of("51 MiB", 51 * SizedPdfFixtures.MIB, 413));
    }

    /** Берёт сохранённый PDF для трёх основных размеров и создаёт точные граничные варианты. */
    private static byte[] pdfForSize(long targetBytes) throws IOException {
        if (targetBytes == 36 * SizedPdfFixtures.MIB) {
            return Files.readAllBytes(SizedPdfFixtures.savedPdf(36));
        }
        if (targetBytes == SizedPdfFixtures.MAX_UPLOAD_BYTES) {
            return Files.readAllBytes(SizedPdfFixtures.savedPdf(50));
        }
        if (targetBytes == 51 * SizedPdfFixtures.MIB) {
            return Files.readAllBytes(SizedPdfFixtures.savedPdf(51));
        }
        return SizedPdfFixtures.pdfBytesOfSize(targetBytes);
    }

    /** Проверяет page/text/ordinal и модель в фактических payload Qdrant для каждого чанка. */
    private void verifyIndexedPages(UUID documentId, List<String> pageTexts) throws Exception {
        for (int ordinal = 0; ordinal < pageTexts.size(); ordinal++) {
            int expectedOrdinal = ordinal;
            TestEvidence.step(
                    "Проверить чанк " + ordinal + " в Qdrant",
                    List.of(
                            new TestEvidence.Input("documentId", documentId),
                            new TestEvidence.Input("ordinal", ordinal),
                            new TestEvidence.Input(
                                    "ожидаемый текст страницы", pageTexts.get(ordinal))),
                    () ->
                            qdrant.retrieveAsync(
                                            STUB_COLLECTION,
                                            id(chunkId(documentId, expectedOrdinal)),
                                            true,
                                            true,
                                            null)
                                    .get(10, TimeUnit.SECONDS),
                    actual -> {
                        assertThat(actual).hasSize(1);
                        RetrievedPoint point = actual.getFirst();
                        Map<String, Value> payload = point.getPayloadMap();
                        TestEvidence.output("Фактический payload Qdrant", payloadEvidence(payload));
                        assertThat(payload.get("documentId").getStringValue())
                                .isEqualTo(documentId.toString());
                        assertThat(payload.get("text").getStringValue())
                                .isEqualTo(pageTexts.get(expectedOrdinal));
                        assertThat(payload.get("page").getIntegerValue())
                                .isEqualTo(expectedOrdinal + 1);
                        assertThat(payload.get("ordinal").getIntegerValue())
                                .isEqualTo(expectedOrdinal);
                        assertThat(payload.get("embeddingModel").getStringValue())
                                .isEqualTo("stub-v1");
                        assertThat(payload.get("stub").getBoolValue()).isTrue();
                    });
        }
    }

    /** Преобразует значения protobuf payload в простые значения для evidence Allure. */
    private Map<String, Object> payloadEvidence(Map<String, Value> payload) {
        Map<String, Object> evidence = new java.util.LinkedHashMap<>();
        payload.forEach(
                (name, value) ->
                        evidence.put(
                                name,
                                switch (value.getKindCase()) {
                                    case NULL_VALUE -> null;
                                    case DOUBLE_VALUE -> value.getDoubleValue();
                                    case INTEGER_VALUE -> value.getIntegerValue();
                                    case STRING_VALUE -> value.getStringValue();
                                    case BOOL_VALUE -> value.getBoolValue();
                                    case STRUCT_VALUE, LIST_VALUE -> value.toString();
                                    case KIND_NOT_SET -> "KIND_NOT_SET";
                                }));
        return evidence;
    }

    /** Проверяет чанки реального отчёта и два значения инфляционных ожиданий на странице 2. */
    private void verifyCbrChunks(UUID documentId, int chunkCount) throws Exception {
        List<String> pageTwoTexts = new ArrayList<>();
        for (int ordinal = 0; ordinal < chunkCount; ordinal++) {
            int expectedOrdinal = ordinal;
            TestEvidence.step(
                    "Проверить чанк отчёта ЦБ " + ordinal + " в Qdrant",
                    List.of(
                            new TestEvidence.Input("documentId", documentId),
                            new TestEvidence.Input("ordinal", ordinal)),
                    () ->
                            qdrant.retrieveAsync(
                                            STUB_COLLECTION,
                                            id(chunkId(documentId, expectedOrdinal)),
                                            true,
                                            true,
                                            null)
                                    .get(10, TimeUnit.SECONDS),
                    actual -> {
                        assertThat(actual).hasSize(1);
                        Map<String, Value> payload = actual.getFirst().getPayloadMap();
                        TestEvidence.output("Фактический payload Qdrant", payloadEvidence(payload));
                        assertThat(payload.get("documentId").getStringValue())
                                .isEqualTo(documentId.toString());
                        assertThat(payload.get("ordinal").getIntegerValue())
                                .isEqualTo(expectedOrdinal);
                        assertThat(payload.get("page").getIntegerValue()).isBetween(1L, 11L);
                        assertThat(payload.get("embeddingModel").getStringValue())
                                .isEqualTo("stub-v1");
                        assertThat(payload.get("stub").getBoolValue()).isTrue();
                        if (payload.get("page").getIntegerValue() == 2) {
                            pageTwoTexts.add(payload.get("text").getStringValue());
                        }
                    });
        }

        String pageTwoText = String.join(" ", pageTwoTexts);
        TestEvidence.step(
                "Проверить опубликованные значения на странице 2",
                List.of(new TestEvidence.Input("Тексты чанков страницы 2", pageTwoTexts)),
                () -> pageTwoText,
                actual -> {
                    assertThat(actual).contains("12,7%", "9,8%");
                });
    }

    /** Проверяет повторное чтение сохранённых метаданных внутри отдельного HTTP шага. */
    private void verifyPersistedMetadata(
            UUID documentId, String token, io.restassured.response.Response upload)
            throws Exception {
        var uploadedMetadata = json(upload);
        TestEvidence.step(
                "Прочитать сохранённые метаданные документа",
                List.of(new TestEvidence.Input("documentId", documentId)),
                () -> backend.get("/documents/" + documentId, token),
                actual -> {
                    TestEvidence.output(
                            "Фактический ответ чтения метаданных",
                            Map.of("status", actual.statusCode(), "body", actual.asString()));
                    assertThat(actual.statusCode()).isEqualTo(200);
                    assertThat(json(actual).path("sha256").asText())
                            .isEqualTo(uploadedMetadata.path("sha256").asText());
                });
    }

    /** Проверяет скачивание исходных байтов документа с настоящим JWT. */
    private void verifyDownloadedOriginal(UUID documentId, String token, byte[] pdf)
            throws Exception {
        TestEvidence.step(
                "Скачать оригинальный PDF и сверить байты",
                List.of(
                        new TestEvidence.Input("documentId", documentId),
                        new TestEvidence.Input("Ожидаемый PDF", pdf)),
                () -> backend.getBytes("/documents/" + documentId + "/file", token),
                actual -> {
                    assertThat(actual.statusCode()).isEqualTo(200);
                    assertThat(actual.getContentType()).startsWith("application/pdf");
                    assertThat(actual.asByteArray()).containsExactly(pdf);
                });
    }

    /** Проверяет исходные байты и SHA-256 скачанного отчёта ЦБ. */
    private void verifyCbrOriginal(UUID documentId, String token, byte[] pdf, String expectedSha256)
            throws Exception {
        TestEvidence.step(
                "Скачать исходный PDF отчёта ЦБ и проверить контрольную сумму",
                List.of(
                        new TestEvidence.Input("documentId", documentId),
                        new TestEvidence.Input("Ожидаемый SHA-256", expectedSha256)),
                () -> backend.getBytes("/documents/" + documentId + "/file", token),
                actual -> {
                    byte[] downloaded = actual.asByteArray();
                    String actualSha256 = sha256(downloaded);
                    TestEvidence.output("Фактический SHA-256 скачанного PDF", actualSha256);
                    assertThat(actual.statusCode()).isEqualTo(200);
                    assertThat(actual.getContentType()).startsWith("application/pdf");
                    assertThat(downloaded).containsExactly(pdf);
                    assertThat(actualSha256).isEqualTo(expectedSha256);
                });
    }

    /** Вычисляет SHA-256 для сравнения оригинального публичного PDF. */
    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException impossible) {
            throw new AssertionError(impossible);
        }
    }

    /** Запоминает UUID сразу после успешного POST для очистки при падении последующих проверок. */
    private void recordDocumentIdForCleanup(io.restassured.response.Response response)
            throws Exception {
        if (response.statusCode() == 201) {
            String location = response.getHeader(HttpHeaders.LOCATION);
            try {
                documentId = UUID.fromString(location.substring(location.lastIndexOf('/') + 1));
            } catch (RuntimeException missingOrInvalidLocation) {
                documentId = UUID.fromString(json(response).path("documentId").asText());
            }
            if (!documentIdsForCleanup.contains(documentId)) {
                documentIdsForCleanup.add(documentId);
            }
        }
    }

    /** Удаляет только артефакты документов, загруженных позитивными сценариями. */
    @AfterEach
    void removeUploadedArtifacts() throws Exception {
        if (documentIdsForCleanup.isEmpty()) return;

        Allure.step(
                "Удалить артефакты загруженных тестовых документов",
                () -> {
                    for (UUID idToDelete : documentIdsForCleanup) {
                        try {
                            if (qdrant.collectionExistsAsync(
                                            STUB_COLLECTION, Duration.ofSeconds(10))
                                    .get(10, TimeUnit.SECONDS)) {
                                Filter filter =
                                        Filter.newBuilder()
                                                .addMust(
                                                        matchKeyword(
                                                                "documentId",
                                                                idToDelete.toString()))
                                                .build();
                                var deletion =
                                        qdrant.deleteAsync(
                                                        STUB_COLLECTION,
                                                        filter,
                                                        Duration.ofSeconds(10))
                                                .get(10, TimeUnit.SECONDS);
                                assertThat(deletion.getStatus()).isEqualTo(UpdateStatus.Completed);
                            }
                        } finally {
                            try {
                                storage.delete(storage.relativePath(idToDelete));
                            } finally {
                                try {
                                    jdbc.update("DELETE FROM documents WHERE id = ?", idToDelete);
                                } finally {
                                    removeEmptyDocumentStorageDirectories(idToDelete);
                                }
                            }
                        }
                    }
                });
    }

    /** Сохраняет относительные пути и размеры файлов до или после отклонённой загрузки. */
    private Map<String, Long> snapshotStoredFiles() throws IOException {
        Path root = Path.of(storagePath);
        if (!Files.exists(root)) return Map.of();
        Map<String, Long> filesByPath = new TreeMap<>();
        try (Stream<Path> files = Files.walk(root)) {
            for (Path path : files.filter(Files::isRegularFile).toList()) {
                filesByPath.put(root.relativize(path).toString(), Files.size(path));
            }
        }
        return filesByPath;
    }

    /** Находит корень ML-образа рядом с корнем Maven-модуля, как базовый класс находит realm Keycloak. */
    private static Path mlDirectory() {
        Path directory = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        while (directory != null) {
            Path candidate = directory.resolve("ml");
            if (Files.isRegularFile(candidate.resolve("Dockerfile"))) return candidate;
            directory = directory.getParent();
        }
        throw new IllegalStateException(
                "Cannot find ml/Dockerfile under the test working directory");
    }
}
