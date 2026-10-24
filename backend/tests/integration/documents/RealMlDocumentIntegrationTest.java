package alpha.business.insight.core.qa.documents.integration;

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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.ImageFromDockerfile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static io.qdrant.client.ConditionFactory.matchKeyword;
import static alpha.business.insight.core.service.DocumentVectorStore.STUB_COLLECTION;
import static alpha.business.insight.core.service.DocumentVectorStore.chunkId;
import static io.qdrant.client.PointIdFactory.id;
import static org.assertj.core.api.Assertions.assertThat;

/** Проверяет загрузку документа через настоящий Python ML-сервис и реальные хранилища. */
@Epic("Backend")
@Feature("Документы аналитики банка")
@Story("Реальная обработка PDF Python ML-сервисом")
@Tag("integration")
@Tag("documents")
@Tag("ml")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"ml.stub=false", "spring.ai.model.chat=none"})
class RealMlDocumentIntegrationTest extends RealStackE2eTest {
    private static final GenericContainer<?> PYTHON_ML = new GenericContainer<>(
            new ImageFromDockerfile("alfa-business-insights-real-ml-test", false)
                    .withFileFromPath(".", mlDirectory()))
            .withExposedPorts(8000)
            .withEnv("ML_EMBEDDER", "stub")
            .withEnv("ML_STUB", "false")
            .waitingFor(Wait.forHttp("/health").forPort(8000).forStatusCode(200)
                    .withStartupTimeout(Duration.ofMinutes(3)));

    @Autowired
    private QdrantClient qdrant;

    @Autowired
    private DocumentStorage storage;

    @org.springframework.beans.factory.annotation.Value("${documents.storage-path}")
    private String storagePath;

    private UUID documentId;

    static {
        PYTHON_ML.start();
    }

    /** Передаёт URL собранного Python-образа Spring-клиенту Feign. */
    @DynamicPropertySource
    static void realMlUrl(DynamicPropertyRegistry properties) {
        properties.add("ml.url", () -> "http://" + PYTHON_ML.getHost() + ":" + PYTHON_ML.getMappedPort(8000));
    }

    /** Проверяет загрузку PDF, индексацию чанков и скачивание оригинала через живые сервисы. */
    @Test
    void TestIntegrationUploadPdfThroughRealPython() throws Exception {
        List<String> pageTexts = List.of("Revenue increased to 120 dollars.", "Operating costs were 45 dollars.");
        byte[] pdf = SyntheticPdf.textDocument(pageTexts);
        var upload = new SyntheticPdf.MultipartUpload("quarterly-report.pdf", "application/pdf", pdf,
                "2026-10-24", "two-page PDF with real extractable text for the Python ML service");

        Allure.step("Загрузить многостраничный PDF и проверить реальную обработку", () -> {
            String token = Allure.step("Получить JWT системного администратора",
                    () -> keycloak.accessToken("sysadmin", "sysadmin-dev"));
            var response = TestEvidence.step("Загрузить PDF через backend и Python ML",
                    List.of(new TestEvidence.Input("PDF", pdf),
                            new TestEvidence.Input("Имя файла", upload.filename()),
                            new TestEvidence.Input("Текст страниц", pageTexts)),
                    () -> {
                        var actual = backend.postMultipart("/admin/documents", upload, token);
                        recordDocumentIdForCleanup(actual);
                        return actual;
                    }, actual -> {
                        TestEvidence.output("Фактический HTTP-ответ загрузки", Map.of(
                                "status", actual.statusCode(),
                                "location", String.valueOf(actual.getHeader(HttpHeaders.LOCATION)),
                                "body", actual.asString()));
                        assertThat(actual.statusCode()).isEqualTo(201);
                        var metadata = json(actual);
                        assertThat(documentId).isNotNull();
                        assertThat(metadata.path("title").asText()).isEqualTo("E2E synthetic report");
                        assertThat(metadata.path("fileName").asText()).isEqualTo("quarterly-report.pdf");
                        assertThat(metadata.path("sizeBytes").asLong()).isEqualTo(pdf.length);
                        assertThat(metadata.path("stub").asBoolean()).isTrue();
                        assertThat(metadata.path("embeddingModel").asText()).isEqualTo("stub-v1");
                        assertThat(metadata.path("embeddingDimension").asInt()).isEqualTo(8);
                        assertThat(metadata.path("chunkCount").asInt()).isEqualTo(pageTexts.size());
                        assertThat(metadata.path("schemaVersion").asInt()).isEqualTo(1);
                        assertThat(metadata.path("sha256").asText()).hasSize(64);
                        assertThat(actual.getHeader(HttpHeaders.LOCATION)).contains(documentId.toString());
                        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM documents WHERE id = ?",
                                Long.class, documentId)).isEqualTo(1L);
                    });

            verifyPersistedMetadata(documentId, token, response);
            verifyIndexedPages(documentId, pageTexts);
            verifyDownloadedOriginal(documentId, token, pdf);
        });
    }

    /** Проверяет обработку опубликованного отчёта ЦБ, извлечённый текст и исходный PDF. */
    @Test
    void TestIntegrationIndexPublishedBankReport() throws Exception {
        byte[] pdf = Files.readAllBytes(mlDirectory().resolve("tests/fixtures/pdf/cbr-inflation-expectations-2024-01.pdf"));
        String expectedSha256 = "ad659e26473e98f7eb1994cb35c0b3df13fc4d7732dc8fd61b5eeddd200e0a02";
        var upload = new SyntheticPdf.MultipartUpload("cbr-inflation-expectations-2024-01.pdf", "application/pdf",
                pdf, "2024-01-01", "Опубликованный отчёт Банка России об инфляционных ожиданиях, 11 страниц");

        Allure.step("Индексировать опубликованный отчёт Банка России", () -> {
            String token = Allure.step("Получить JWT системного администратора",
                    () -> keycloak.accessToken("sysadmin", "sysadmin-dev"));
            var response = TestEvidence.step("Загрузить реальный PDF отчёта ЦБ через Python ML",
                    List.of(new TestEvidence.Input("Файл отчёта", pdf),
                            new TestEvidence.Input("Ожидаемый SHA-256", expectedSha256)),
                    () -> {
                        var actual = backend.postMultipart("/admin/documents", upload, token);
                        recordDocumentIdForCleanup(actual);
                        return actual;
                    }, actual -> {
                        TestEvidence.output("Фактический ответ загрузки отчёта",
                                Map.of("status", actual.statusCode(), "body", actual.asString()));
                        assertThat(actual.statusCode()).isEqualTo(201);
                        var metadata = json(actual);
                        assertThat(documentId).isNotNull();
                        assertThat(metadata.path("fileName").asText()).isEqualTo(upload.filename());
                        assertThat(metadata.path("sizeBytes").asLong()).isEqualTo(pdf.length);
                        assertThat(metadata.path("sha256").asText()).isEqualTo(expectedSha256);
                        assertThat(metadata.path("stub").asBoolean()).isTrue();
                        assertThat(metadata.path("embeddingModel").asText()).isEqualTo("stub-v1");
                        assertThat(metadata.path("embeddingDimension").asInt()).isEqualTo(8);
                        assertThat(metadata.path("chunkCount").asInt()).isPositive();
                        assertThat(metadata.path("schemaVersion").asInt()).isEqualTo(1);
                        assertThat(actual.getHeader(HttpHeaders.LOCATION)).contains(documentId.toString());
                        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM documents WHERE id = ?",
                                Long.class, documentId)).isEqualTo(1L);
                    });

            var metadata = json(response);
            verifyPersistedMetadata(documentId, token, response);
            verifyCbrChunks(documentId, metadata.path("chunkCount").asInt());
            verifyCbrOriginal(documentId, token, pdf, expectedSha256);
        });
    }

    /** Проверяет отказ Python ML для PDF без текста, очистку данных и спорный HTTP-контракт. */
    @Test
    void TestIntegrationRejectTextlessPdfWithoutArtifacts() throws Exception {
        byte[] pdf = SyntheticPdf.textDocument(List.of(""));
        var upload = new SyntheticPdf.MultipartUpload("blank-page.pdf", "application/pdf", pdf,
                "2026-10-24", "valid PDF signature with a physical page but no extractable text");

        Allure.step("Отклонить PDF без текста и проверить компенсацию загрузки", () -> {
            String token = Allure.step("Получить JWT системного администратора",
                    () -> keycloak.accessToken("sysadmin", "sysadmin-dev"));
            var response = TestEvidence.step("Отправить PDF без извлекаемого текста",
                    List.of(new TestEvidence.Input("PDF", pdf),
                            new TestEvidence.Input("Имя файла", upload.filename())),
                    () -> backend.postMultipart("/admin/documents", upload, token), actual ->
                            TestEvidence.output("Фактический ответ backend после отказа Python",
                                    Map.of("status", actual.statusCode(), "body", actual.asString())));

            TestEvidence.step("Проверить отсутствие артефактов неудачной загрузки",
                    List.of(new TestEvidence.Input("Имя файла", upload.filename())),
                    () -> Map.of("documentRows", jdbc.queryForObject(
                                    "SELECT COUNT(*) FROM documents WHERE file_name = ?", Long.class, upload.filename()),
                            "matchingOriginalBytesPresent", hasStoredPdf(pdf)), actual -> {
                        assertThat(actual.get("documentRows")).isEqualTo(0L);
                        assertThat(actual.get("matchingOriginalBytesPresent")).isEqualTo(false);
                    });

            Allure.step("Проверить опубликованный HTTP-контракт отказа ML", () ->
                    assertThat(response.statusCode())
                            .as("Контракт ожидает 502; текущая Feign-обработка отдаёт 503")
                            .isEqualTo(502));
        });
    }

    /** Проверяет page/text/ordinal и модель в фактических payload Qdrant для каждого чанка. */
    private void verifyIndexedPages(UUID documentId, List<String> pageTexts) throws Exception {
        for (int ordinal = 0; ordinal < pageTexts.size(); ordinal++) {
            int expectedOrdinal = ordinal;
            TestEvidence.step("Проверить чанк " + ordinal + " в Qdrant",
                    List.of(new TestEvidence.Input("documentId", documentId),
                            new TestEvidence.Input("ordinal", ordinal),
                            new TestEvidence.Input("ожидаемый текст страницы", pageTexts.get(ordinal))),
                    () -> qdrant.retrieveAsync(STUB_COLLECTION,
                            id(chunkId(documentId, expectedOrdinal)), true, true, null)
                            .get(10, TimeUnit.SECONDS), actual -> {
                        assertThat(actual).hasSize(1);
                        RetrievedPoint point = actual.getFirst();
                        Map<String, Value> payload = point.getPayloadMap();
                        TestEvidence.output("Фактический payload Qdrant", payloadEvidence(payload));
                        assertThat(payload.get("documentId").getStringValue()).isEqualTo(documentId.toString());
                        assertThat(payload.get("text").getStringValue()).isEqualTo(pageTexts.get(expectedOrdinal));
                        assertThat(payload.get("page").getIntegerValue()).isEqualTo(expectedOrdinal + 1);
                        assertThat(payload.get("ordinal").getIntegerValue()).isEqualTo(expectedOrdinal);
                        assertThat(payload.get("embeddingModel").getStringValue()).isEqualTo("stub-v1");
                        assertThat(payload.get("stub").getBoolValue()).isTrue();
                    });
        }
    }

    /** Преобразует значения protobuf payload в простые значения для evidence Allure. */
    private Map<String, Object> payloadEvidence(Map<String, Value> payload) {
        Map<String, Object> evidence = new java.util.LinkedHashMap<>();
        payload.forEach((name, value) -> evidence.put(name, switch (value.getKindCase()) {
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
            TestEvidence.step("Проверить чанк отчёта ЦБ " + ordinal + " в Qdrant",
                    List.of(new TestEvidence.Input("documentId", documentId),
                            new TestEvidence.Input("ordinal", ordinal)),
                    () -> qdrant.retrieveAsync(STUB_COLLECTION,
                            id(chunkId(documentId, expectedOrdinal)), true, true, null)
                            .get(10, TimeUnit.SECONDS), actual -> {
                        assertThat(actual).hasSize(1);
                        Map<String, Value> payload = actual.getFirst().getPayloadMap();
                        TestEvidence.output("Фактический payload Qdrant", payloadEvidence(payload));
                        assertThat(payload.get("documentId").getStringValue()).isEqualTo(documentId.toString());
                        assertThat(payload.get("ordinal").getIntegerValue()).isEqualTo(expectedOrdinal);
                        assertThat(payload.get("page").getIntegerValue()).isBetween(1L, 11L);
                        assertThat(payload.get("embeddingModel").getStringValue()).isEqualTo("stub-v1");
                        assertThat(payload.get("stub").getBoolValue()).isTrue();
                        if (payload.get("page").getIntegerValue() == 2) {
                            pageTwoTexts.add(payload.get("text").getStringValue());
                        }
                    });
        }

        String pageTwoText = String.join(" ", pageTwoTexts);
        TestEvidence.step("Проверить опубликованные значения на странице 2",
                List.of(new TestEvidence.Input("Тексты чанков страницы 2", pageTwoTexts)),
                () -> pageTwoText, actual -> {
                    assertThat(actual).contains("12,7%", "9,8%");
                });
    }

    /** Проверяет повторное чтение сохранённых метаданных внутри отдельного HTTP шага. */
    private void verifyPersistedMetadata(UUID documentId, String token,
                                         io.restassured.response.Response upload) throws Exception {
        var uploadedMetadata = json(upload);
        TestEvidence.step("Прочитать сохранённые метаданные документа",
                List.of(new TestEvidence.Input("documentId", documentId)),
                () -> backend.get("/documents/" + documentId, token), actual -> {
                    TestEvidence.output("Фактический ответ чтения метаданных", Map.of(
                            "status", actual.statusCode(), "body", actual.asString()));
                    assertThat(actual.statusCode()).isEqualTo(200);
                    assertThat(json(actual).path("sha256").asText())
                            .isEqualTo(uploadedMetadata.path("sha256").asText());
                });
    }

    /** Проверяет скачивание исходных байтов документа с настоящим JWT. */
    private void verifyDownloadedOriginal(UUID documentId, String token, byte[] pdf) throws Exception {
        TestEvidence.step("Скачать оригинальный PDF и сверить байты",
                List.of(new TestEvidence.Input("documentId", documentId),
                        new TestEvidence.Input("Ожидаемый PDF", pdf)),
                () -> backend.getBytes("/documents/" + documentId + "/file", token), actual -> {
                    assertThat(actual.statusCode()).isEqualTo(200);
                    assertThat(actual.getContentType()).startsWith("application/pdf");
                    assertThat(actual.asByteArray()).containsExactly(pdf);
                });
    }

    /** Проверяет исходные байты и SHA-256 скачанного отчёта ЦБ. */
    private void verifyCbrOriginal(UUID documentId, String token, byte[] pdf, String expectedSha256)
            throws Exception {
        TestEvidence.step("Скачать исходный PDF отчёта ЦБ и проверить контрольную сумму",
                List.of(new TestEvidence.Input("documentId", documentId),
                        new TestEvidence.Input("Ожидаемый SHA-256", expectedSha256)),
                () -> backend.getBytes("/documents/" + documentId + "/file", token), actual -> {
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
    private void recordDocumentIdForCleanup(io.restassured.response.Response response) throws Exception {
        if (response.statusCode() == 201) {
            String location = response.getHeader(HttpHeaders.LOCATION);
            try {
                documentId = UUID.fromString(location.substring(location.lastIndexOf('/') + 1));
            } catch (RuntimeException missingOrInvalidLocation) {
                documentId = UUID.fromString(json(response).path("documentId").asText());
            }
        }
    }

    /** Удаляет только артефакты UUID, созданного позитивным сценарием. */
    @AfterEach
    void removeUploadedArtifacts() throws Exception {
        if (documentId == null) return;
        UUID idToDelete = documentId;
        Allure.step("Удалить тестовые точки Qdrant, строку PostgreSQL и PDF", () -> {
            try {
                if (qdrant.collectionExistsAsync(STUB_COLLECTION, Duration.ofSeconds(10))
                        .get(10, TimeUnit.SECONDS)) {
                    Filter filter = Filter.newBuilder()
                            .addMust(matchKeyword("documentId", idToDelete.toString())).build();
                    var deletion = qdrant.deleteAsync(STUB_COLLECTION, filter, Duration.ofSeconds(10))
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
        });
    }

    /** Проверяет, что исходные байты не остались в хранилище после отказа Python. */
    private boolean hasStoredPdf(byte[] expected) throws IOException {
        Path root = Path.of(storagePath);
        if (!Files.exists(root)) return false;
        try (Stream<Path> files = Files.walk(root)) {
            return files.filter(Files::isRegularFile)
                    .anyMatch(path -> hasExactBytes(path, expected));
        }
    }

    /** Сверяет файл из хранилища с байтами исходного PDF. */
    private static boolean hasExactBytes(Path path, byte[] expected) {
        try {
            return java.util.Arrays.equals(Files.readAllBytes(path), expected);
        } catch (IOException exception) {
            throw new AssertionError("Не удалось прочитать файл тестового хранилища " + path, exception);
        }
    }

    /** Находит корень ML-образа рядом с корнем Maven-модуля, как базовый класс находит realm Keycloak. */
    private static Path mlDirectory() {
        Path directory = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        while (directory != null) {
            Path candidate = directory.resolve("ml");
            if (Files.isRegularFile(candidate.resolve("Dockerfile"))) return candidate;
            directory = directory.getParent();
        }
        throw new IllegalStateException("Cannot find ml/Dockerfile under the test working directory");
    }
}
