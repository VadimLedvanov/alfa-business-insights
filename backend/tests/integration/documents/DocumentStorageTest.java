package alpha.business.insight.core.document;

import static org.assertj.core.api.Assertions.assertThat;

import alpha.business.insight.core.qa.helpers.TestEvidence;
import alpha.business.insight.core.service.DocumentStorage;
import io.qameta.allure.Allure;
import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Проверяет запись, удаление и чтение ранее загруженных PDF в файловом хранилище. */
@Tag("integration")
@Tag("documents")
class DocumentStorageTest {
    @TempDir Path root;

    /** Проверяет запись PDF в три каталога и удаление сохранённого файла. */
    @Test
    void storesPdfInThreeDirectoriesFromFirstSixUuidCharacters() throws Exception {
        var storage = new DocumentStorage(root.toString());
        UUID id = UUID.fromString("abcdef12-3456-7890-abcd-ef1234567890");
        byte[] pdf = "%PDF-1.4".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        Path file = root.resolve("ab/cd/ef/abcdef12-3456-7890-abcd-ef1234567890.pdf");

        TestEvidence.step(
                "Сохранить и прочитать загруженный PDF",
                List.of(
                        new TestEvidence.Input("documentId", id),
                        new TestEvidence.Input("pdfBytes", pdf)),
                () -> {
                    Allure.addAttachment(
                            "Входной PDF",
                            "application/pdf",
                            new ByteArrayInputStream(pdf),
                            ".pdf");
                    storage.write(id, pdf);
                    byte[] readBytes = storage.read(id).getContentAsByteArray();
                    Allure.addAttachment(
                            "PDF, прочитанный из хранилища",
                            "application/pdf",
                            new ByteArrayInputStream(readBytes),
                            ".pdf");
                    return Map.of(
                            "relativePath",
                            storage.relativePath(id),
                            "fileExists",
                            Files.exists(file),
                            "readBytes",
                            readBytes);
                },
                actual -> {
                    assertThat(file).hasBinaryContent(pdf);
                    assertThat(actual.get("readBytes")).isEqualTo(pdf);
                });

        TestEvidence.step(
                "Удалить сохранённый PDF",
                List.of(
                        new TestEvidence.Input("documentId", id),
                        new TestEvidence.Input("relativePath", storage.relativePath(id))),
                () -> {
                    storage.delete(storage.relativePath(id));
                    return Map.of("fileExistsAfterDelete", Files.exists(file));
                },
                actual -> assertThat(actual.get("fileExistsAfterDelete")).isEqualTo(false));
    }

    /** Проверяет чтение файла, сохранённого до перехода на шардированные каталоги. */
    @Test
    void stillReadsPreviouslyUploadedFlatFiles() throws Exception {
        var storage = new DocumentStorage(root.toString());
        UUID id = UUID.randomUUID();
        byte[] pdf = "%PDF-1.4".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        Path legacyFile = root.resolve(id + ".pdf");
        Files.write(legacyFile, pdf);

        TestEvidence.step(
                "Прочитать старый файл из корневого каталога",
                List.of(
                        new TestEvidence.Input("documentId", id),
                        new TestEvidence.Input("legacyRelativePath", root.relativize(legacyFile)),
                        new TestEvidence.Input("pdfBytes", pdf)),
                () -> {
                    Allure.addAttachment(
                            "Входной PDF",
                            "application/pdf",
                            new ByteArrayInputStream(pdf),
                            ".pdf");
                    byte[] readBytes = storage.read(id).getContentAsByteArray();
                    Allure.addAttachment(
                            "PDF, прочитанный по прежнему пути",
                            "application/pdf",
                            new ByteArrayInputStream(readBytes),
                            ".pdf");
                    return readBytes;
                },
                actualBytes -> assertThat(actualBytes).isEqualTo(pdf));
    }

    /** Проверяет отказ очистки по относительному пути за пределами хранилища. */
    @Test
    void rejectsCleanupPathsOutsideStorage() {
        var storage = new DocumentStorage(root.toString());

        TestEvidence.stepThrows(
                "Отклонить путь очистки за пределами хранилища",
                List.of(new TestEvidence.Input("relativePath", "../other.pdf")),
                () -> storage.delete("../other.pdf"),
                failure -> assertThat(failure).isInstanceOf(IllegalArgumentException.class));
    }
}
