package alpha.business.insight.core.qa.documents.unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import alpha.business.insight.core.ml.DocumentAnalysis;
import alpha.business.insight.core.ml.DocumentAnalyzer;
import alpha.business.insight.core.ml.DocumentChunk;
import alpha.business.insight.core.ml.StubDocumentAnalyzer;
import alpha.business.insight.core.model.KnowledgeDocument;
import alpha.business.insight.core.qa.helpers.TestEvidence;
import alpha.business.insight.core.repository.DocumentRepository;
import alpha.business.insight.core.service.DocumentService;
import alpha.business.insight.core.service.DocumentStorage;
import alpha.business.insight.core.service.DocumentVectorStore;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import io.qameta.allure.Story;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

/** Проверяет загрузку документа, метаданные и компенсационную очистку в изоляции. */
@ExtendWith(MockitoExtension.class)
@Epic("Backend")
@Feature("Документы")
@Story("Загрузка, чтение метаданных и компенсационная очистка PDF")
@Tag("unit")
@Tag("documents")
class DocumentServiceTest {
    private static final byte[] PDF = "%PDF-1.7\nexample\n%%EOF".getBytes(StandardCharsets.UTF_8);

    @TempDir Path root;
    @Mock private DocumentRepository repository;
    private DocumentStorage storage;
    @Mock private DocumentAnalyzer analyzer;
    @Mock private DocumentVectorStore vectors;
    private DocumentService service;

    /** Создаёт хранилище во временной папке и собирает сервис с Mockito-зависимостями. */
    @BeforeEach
    void setUp() {
        storage = new DocumentStorage(root.toString());
        service = new DocumentService(repository, storage, analyzer, vectors);
    }

    /** Проверяет сохранение PDF, нормализацию метаданных и индексацию вектора. */
    @Test
    @DisplayName("TestUnitUploadPdfAndPersistMetadata")
    void TestUnitUploadPdfAndPersistMetadata() throws Exception {
        stubSaveAndFlush();
        useStubAnalyzer();
        LocalDate publicationDate = LocalDate.of(2026, 10, 1);
        String filename = "reports\\rates.pdf";
        String title = "  Обзор ставки  ";

        TestEvidence.step(
                "Загрузить PDF и получить метаданные успешно проиндексированного документа",
                List.of(
                        new TestEvidence.Input("Файл", fileDetails(filename)),
                        new TestEvidence.Input("Заголовок", title),
                        new TestEvidence.Input("Дата публикации", publicationDate)),
                () -> service.upload(file(filename), title, publicationDate),
                actual -> {
                    String actualSha256 = sha256(PDF);
                    byte[] storedPdf = storage.read(actual.documentId()).getContentAsByteArray();
                    assertThat(actual.title())
                            .as("нормализованный заголовок")
                            .isEqualTo("Обзор ставки");
                    assertThat(actual.fileName())
                            .as("имя после удаления каталогов из пути загрузки")
                            .isEqualTo("rates.pdf");
                    assertThat(actual.sizeBytes())
                            .as("размер сохранённого PDF")
                            .isEqualTo(PDF.length);
                    assertThat(actual.sha256())
                            .as("SHA-256 исходных байтов")
                            .isEqualTo(actualSha256);
                    assertThat(actual.publicationDate())
                            .as("дата публикации")
                            .isEqualTo(publicationDate);
                    assertThat(actual.chunkCount()).as("число чанков анализа").isEqualTo(1);
                    assertThat(storedPdf).as("байты PDF в хранилище").isEqualTo(PDF);
                    TestEvidence.output(
                            "Сохранённый оригинал", fileDetails(actual.fileName(), storedPdf));
                    verify(vectors)
                            .index(any(DocumentAnalysis.class), eq(publicationDate), anyLong());
                });
    }

    /** Проверяет имя и заголовок по умолчанию для файла без имени. */
    @Test
    @DisplayName("TestUnitUploadWithoutFilenameUsesDefault")
    void TestUnitUploadWithoutFilenameUsesDefault() throws Exception {
        stubSaveAndFlush();
        useStubAnalyzer();
        MultipartFile fileWithoutName = new MockMultipartFile("file", null, "application/pdf", PDF);

        var metadata =
                TestEvidence.step(
                        "Загрузить PDF без имени файла",
                        List.of(
                                new TestEvidence.Input(
                                        "Файл",
                                        Map.of(
                                                "contentType",
                                                "application/pdf",
                                                "filename",
                                                "",
                                                "content",
                                                PDF))),
                        () -> service.upload(fileWithoutName, " ", null),
                        actual -> {
                            assertThat(actual.fileName())
                                    .as("имя документа по умолчанию")
                                    .isEqualTo("document.pdf");
                            assertThat(actual.title())
                                    .as("заголовок по умолчанию")
                                    .isEqualTo("document.pdf");
                            assertThat(storage.read(actual.documentId()).getContentAsByteArray())
                                    .as("байты PDF без переданного имени")
                                    .isEqualTo(PDF);
                            TestEvidence.output(
                                    "Сохранённый оригинал", fileDetails(actual.fileName(), PDF));
                        });
    }

    /** Проверяет отказ для имени с управляющим символом до записи данных. */
    @Test
    @DisplayName("TestUnitRejectControlFilenameBeforeWrites")
    void TestUnitRejectControlFilenameBeforeWrites() throws Exception {
        String invalidFilename = "bad\nname.pdf";

        TestEvidence.stepThrows(
                "Отклонить имя файла с управляющим символом",
                List.of(new TestEvidence.Input("Файл", fileDetails(invalidFilename))),
                () -> service.upload(file(invalidFilename), null, null),
                error -> {
                    assertThat(error)
                            .isInstanceOfSatisfying(
                                    ResponseStatusException.class,
                                    actual ->
                                            assertThat(actual.getStatusCode())
                                                    .isEqualTo(HttpStatus.BAD_REQUEST));
                    TestEvidence.output("Файлы в хранилище", regularFiles());
                    assertThat(regularFiles()).as("файлы после отказа до записи").isEmpty();
                    verify(analyzer, never()).analyze(any(), any());
                    verify(repository, never()).saveAndFlush(any());
                });
    }

    /** Проверяет удаление PDF, если анализатор завершился ошибкой. */
    @Test
    @DisplayName("TestUnitCleanupAfterAnalyzerFailure")
    void TestUnitCleanupAfterAnalyzerFailure() throws Exception {
        IllegalStateException analyzerFailure = new IllegalStateException("ML offline");
        when(analyzer.analyze(any(), any()))
                .thenAnswer(
                        call -> {
                            TestEvidence.output(
                                    "Фактический сбой анализатора",
                                    Map.of(
                                            "type",
                                            analyzerFailure.getClass().getName(),
                                            "message",
                                            analyzerFailure.getMessage()));
                            throw analyzerFailure;
                        });

        TestEvidence.stepThrows(
                "Вернуть 503 и удалить PDF при недоступности ML",
                List.of(new TestEvidence.Input("Файл", fileDetails("rates.pdf"))),
                () -> service.upload(file("rates.pdf"), null, null),
                error -> {
                    assertThat(error)
                            .isInstanceOfSatisfying(
                                    ResponseStatusException.class,
                                    actual ->
                                            assertThat(actual.getStatusCode())
                                                    .isEqualTo(HttpStatus.SERVICE_UNAVAILABLE));
                    TestEvidence.output("Файлы после компенсации", regularFiles());
                    assertThat(regularFiles())
                            .as("файлы после компенсации сбоя анализатора")
                            .isEmpty();
                    verify(vectors, never()).delete(any(), anyString(), anyInt(), anyBoolean());
                    verify(repository, never()).saveAndFlush(any());
                });
    }

    /** Проверяет отказ при ответе анализатора с чужим идентификатором документа. */
    @Test
    @DisplayName("TestUnitRejectForeignAnalysisDocumentId")
    void TestUnitRejectForeignAnalysisDocumentId() throws Exception {
        DocumentAnalysis foreignAnalysis =
                new DocumentAnalysis(
                        UUID.randomUUID(),
                        1,
                        true,
                        "stub-v1",
                        1,
                        List.of(new DocumentChunk(0, 1, "text", List.of(1f))));
        when(analyzer.analyze(any(), any())).thenReturn(foreignAnalysis);

        TestEvidence.stepThrows(
                "Отклонить чужой идентификатор анализа и удалить PDF",
                List.of(
                        new TestEvidence.Input("Файл", fileDetails("rates.pdf")),
                        new TestEvidence.Input("Ответ анализатора", foreignAnalysis)),
                () -> service.upload(file("rates.pdf"), null, null),
                error -> {
                    assertThat(error)
                            .isInstanceOfSatisfying(
                                    ResponseStatusException.class,
                                    actual ->
                                            assertThat(actual.getStatusCode())
                                                    .isEqualTo(HttpStatus.BAD_GATEWAY));
                    TestEvidence.output("Файлы после компенсации", regularFiles());
                    assertThat(regularFiles())
                            .as("файлы после компенсации чужого documentId")
                            .isEmpty();
                    verify(vectors, never()).index(any(), any(), anyLong());
                    verify(repository, never()).saveAndFlush(any());
                });
    }

    /** Проверяет компенсационное удаление PDF и вектора при ошибке индексации. */
    @Test
    @DisplayName("TestUnitCleanupAfterVectorFailure")
    void TestUnitCleanupAfterVectorFailure() throws Exception {
        useStubAnalyzer();
        doThrow(new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE))
                .when(vectors)
                .index(any(), any(), anyLong());

        TestEvidence.stepThrows(
                "Удалить PDF и вектор при ошибке индексации",
                List.of(new TestEvidence.Input("Файл", fileDetails("rates.pdf"))),
                () -> service.upload(file("rates.pdf"), null, null),
                error -> {
                    assertThat(error).isInstanceOf(ResponseStatusException.class);
                    TestEvidence.output("Файлы после компенсации", regularFiles());
                    assertThat(regularFiles())
                            .as("файлы после компенсации ошибки индексации")
                            .isEmpty();
                    verify(vectors).delete(any(), anyString(), anyInt(), anyBoolean());
                    verify(repository, never()).saveAndFlush(any());
                });
    }

    /** Проверяет ответ 404 для неизвестного идентификатора документа. */
    @Test
    @DisplayName("TestUnitReturnNotFoundForUnknownDocument")
    void TestUnitReturnNotFoundForUnknownDocument() {
        UUID id = UUID.randomUUID();
        when(repository.findById(id)).thenReturn(Optional.empty());

        TestEvidence.stepThrows(
                "Вернуть 404 для неизвестного документа",
                List.of(new TestEvidence.Input("documentId", id)),
                () -> service.get(id),
                error ->
                        assertThat(error)
                                .isInstanceOfSatisfying(
                                        ResponseStatusException.class,
                                        actual ->
                                                assertThat(actual.getStatusCode())
                                                        .isEqualTo(HttpStatus.NOT_FOUND)));
    }

    /** Проверяет отказ для нечитаемого файла и отсутствие записей в хранилище. */
    @Test
    @DisplayName("TestUnitRejectUnreadableUpload")
    void TestUnitRejectUnreadableUpload() {
        MultipartFile unreadable =
                new MockMultipartFile("file", "rates.pdf", "application/pdf", PDF) {
                    /** Имитирует сбой чтения тела multipart-файла. */
                    @Override
                    public InputStream getInputStream() throws IOException {
                        throw new IOException("read failed");
                    }
                };

        TestEvidence.stepThrows(
                "Отклонить нечитаемый файл без записи",
                List.of(
                        new TestEvidence.Input(
                                "MultipartFile",
                                Map.of(
                                        "filename",
                                        "rates.pdf",
                                        "contentType",
                                        "application/pdf",
                                        "reportedSize",
                                        PDF.length,
                                        "getInputStream",
                                        "throws IOException: read failed"))),
                () -> service.upload(unreadable, null, null),
                error -> {
                    assertThat(error)
                            .isInstanceOfSatisfying(
                                    ResponseStatusException.class,
                                    actual ->
                                            assertThat(actual.getStatusCode())
                                                    .isEqualTo(HttpStatus.BAD_REQUEST));
                    TestEvidence.output("Файлы после отказа", regularFiles());
                    assertThat(root).as("каталог загрузки после ошибки чтения").isEmptyDirectory();
                });
    }

    /** Создаёт multipart-файл с тестовыми PDF-байтами. */
    private static MockMultipartFile file(String name) {
        return new MockMultipartFile("file", name, "application/pdf", PDF);
    }

    /** Возвращает сохранённую сущность из мока репозитория в успешных сценариях. */
    private void stubSaveAndFlush() {
        when(repository.saveAndFlush(any(KnowledgeDocument.class)))
                .thenAnswer(call -> call.getArgument(0));
    }

    /** Настраивает синтетический анализатор, строящий ответ из фактического PDF. */
    private void useStubAnalyzer() {
        when(analyzer.analyze(any(), any()))
                .thenAnswer(
                        call -> {
                            DocumentAnalysis analysis =
                                    new StubDocumentAnalyzer()
                                            .analyze(call.getArgument(0), call.getArgument(1));
                            TestEvidence.output("Фактический ответ анализатора", analysis);
                            return analysis;
                        });
    }

    /** Возвращает поля файла, пригодные для безопасного приложения к Allure. */
    private static Map<String, Object> fileDetails(String name) {
        return fileDetails(name, PDF);
    }

    /** Добавляет размер и хеш байтов файла к его диагностическим полям. */
    private static Map<String, Object> fileDetails(String name, byte[] content) {
        return Map.of("filename", name, "contentType", "application/pdf", "content", content);
    }

    /** Перечисляет относительные пути обычных файлов во временном хранилище. */
    private List<String> regularFiles() {
        try (var paths = java.nio.file.Files.walk(root)) {
            return paths.filter(java.nio.file.Files::isRegularFile)
                    .map(root::relativize)
                    .map(Path::toString)
                    .toList();
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }

    /** Вычисляет SHA-256 байтов для сверки сохранённого оригинала. */
    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new AssertionError(impossible);
        }
    }
}
