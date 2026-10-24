package alpha.business.insight.core.qa.documents.unit;

import alpha.business.insight.core.qa.helpers.TestEvidence;
import alpha.business.insight.core.service.DocumentStorage;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import io.qameta.allure.Story;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Проверяет запись, чтение, удаление и защиту путей локального хранилища PDF. */
@Epic("Backend")
@Feature("Документы")
@Story("Хранение оригиналов PDF")
@Tag("unit")
@Tag("documents")
class DocumentStorageTest {
    private static final UUID SHARDED_DOCUMENT_ID = UUID.fromString("12345678-1234-5678-9abc-def012345678");
    private static final String SHARDED_RELATIVE_PATH =
            "12/34/56/12345678-1234-5678-9abc-def012345678.pdf";

    @TempDir Path root;

    /** Проверяет сохранение и чтение PDF по шардированному пути. */
    @Test
    @DisplayName("TestUnitStoreAndReadPdfBytes")
    void TestUnitStoreAndReadPdfBytes() throws Exception {
        var storage = new DocumentStorage(root.toString());
        UUID id = SHARDED_DOCUMENT_ID;
        byte[] original = "%PDF-1.7\nТекст\n%%EOF".getBytes(StandardCharsets.UTF_8);

        // Сохраняет и читает PDF через API хранилища.
        TestEvidence.step("Сохранить и прочитать PDF по шардированному пути",
                List.of(new TestEvidence.Input("documentId", id),
                        new TestEvidence.Input("PDF", original)),
                () -> {
                    storage.write(id, original);
                    return Map.of("relativePath", storage.relativePath(id),
                            "file", fileDetails(root.resolve(storage.relativePath(id))),
                            "readBytes", storage.read(id).getContentAsByteArray());
                }, actual -> {
                    assertThat(actual.get("relativePath")).isEqualTo(SHARDED_RELATIVE_PATH);
                    assertThat(root.resolve(SHARDED_RELATIVE_PATH)).hasBinaryContent(original);
                    assertThat(actual.get("readBytes")).isEqualTo(original);
                });
    }

    /** Проверяет отказ перезаписи существующего PDF без изменения сохранённых байтов. */
    @Test
    @DisplayName("TestUnitPreventOverwriteOfStoredPdf")
    void TestUnitPreventOverwriteOfStoredPdf() throws Exception {
        var storage = new DocumentStorage(root.toString());
        UUID id = UUID.randomUUID();
        byte[] original = "%PDF-1.4 first".getBytes(StandardCharsets.UTF_8);
        TestEvidence.step("Сохранить исходную версию PDF",
                List.of(new TestEvidence.Input("documentId", id),
                        new TestEvidence.Input("PDF", original)),
                () -> {
                    storage.write(id, original);
                    return Map.of("relativePath", storage.relativePath(id),
                            "file", fileDetails(root.resolve(storage.relativePath(id))));
                }, actual -> {
                    assertThat(root.resolve(storage.relativePath(id))).hasBinaryContent(original);
                });

        byte[] replacement = "%PDF-1.4 second".getBytes(StandardCharsets.UTF_8);
        TestEvidence.stepThrows("Отклонить перезапись и сохранить исходные байты",
                List.of(new TestEvidence.Input("documentId", id), new TestEvidence.Input("Новый PDF", replacement)),
                () -> storage.write(id, replacement), error -> {
                    assertThat(error).isInstanceOf(FileAlreadyExistsException.class);
                    TestEvidence.output("Существующий файл", fileDetails(root.resolve(storage.relativePath(id))));
                    assertThat(storage.read(id).getContentAsByteArray()).isEqualTo(original);
                });
    }

    /** Проверяет идемпотентное удаление отсутствующего PDF. */
    @Test
    @DisplayName("TestUnitDeleteMissingPdfIdempotently")
    void TestUnitDeleteMissingPdfIdempotently() throws Exception {
        var storage = new DocumentStorage(root.toString());
        String relativePath = storage.relativePath(UUID.randomUUID());

        TestEvidence.step("Повторно удалить отсутствующий файл",
                List.of(new TestEvidence.Input("relativePath", relativePath)), () -> {
                    storage.delete(relativePath);
                    storage.delete(relativePath);
                    return filesUnderRoot();
                }, actual -> assertThat(actual).isEmpty());
    }

    /** Проверяет отказ при попытке выйти за корневой каталог хранилища. */
    @Test
    @DisplayName("TestUnitRejectStoragePathTraversal")
    void TestUnitRejectStoragePathTraversal() {
        var storage = new DocumentStorage(root.toString());

        TestEvidence.stepThrows("Отклонить путь за пределами корня хранилища",
                List.of(new TestEvidence.Input("relativePath", "../../outside.pdf")),
                () -> storage.delete("../../outside.pdf"),
                error -> assertThat(error).isInstanceOf(IllegalArgumentException.class));
    }

    /** Проверяет чтение PDF, сохранённого по прежнему плоскому пути. */
    @Test
    @DisplayName("TestUnitReadLegacyFlatPdf")
    void TestUnitReadLegacyFlatPdf() throws Exception {
        var storage = new DocumentStorage(root.toString());
        UUID id = UUID.randomUUID();
        byte[] original = "%PDF-1.5 legacy".getBytes(StandardCharsets.UTF_8);
        Files.write(root.resolve(id + ".pdf"), original);

        TestEvidence.step("Прочитать PDF, оставшийся в старом плоском формате",
                List.of(new TestEvidence.Input("documentId", id),
                        new TestEvidence.Input("Файл в старом формате", fileDetails(root.resolve(id + ".pdf")))),
                () -> storage.read(id).getContentAsByteArray(), actual -> assertThat(actual).isEqualTo(original));
    }

    /** Формирует фактические сведения о файле для вложения в Allure. */
    private Map<String, Object> fileDetails(Path file) throws Exception {
        byte[] bytes = Files.readAllBytes(file);
        return Map.of(
                "path", root.relativize(file).toString(),
                "content", bytes);
    }

    /** Возвращает относительные пути обычных файлов в тестовом хранилище. */
    private List<String> filesUnderRoot() throws Exception {
        try (var paths = Files.walk(root)) {
            return paths.filter(Files::isRegularFile).map(root::relativize).map(Path::toString).toList();
        }
    }
}
