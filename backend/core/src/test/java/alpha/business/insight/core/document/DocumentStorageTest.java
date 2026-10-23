package alpha.business.insight.core.document;

import alpha.business.insight.core.service.DocumentStorage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DocumentStorageTest {
    @TempDir
    Path root;

    @Test
    void storesPdfInThreeDirectoriesFromFirstSixUuidCharacters() throws Exception {
        var storage = new DocumentStorage(root.toString());
        UUID id = UUID.fromString("abcdef12-3456-7890-abcd-ef1234567890");
        byte[] pdf = "%PDF-1.4".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        storage.write(id, pdf);
        Path file = root.resolve("ab/cd/ef/abcdef12-3456-7890-abcd-ef1234567890.pdf");
        assertThat(file).hasBinaryContent(pdf);
        assertThat(storage.read(id).getContentAsByteArray()).isEqualTo(pdf);
        storage.delete(storage.relativePath(id));
        assertThat(file).doesNotExist();
    }

    @Test
    void stillReadsPreviouslyUploadedFlatFiles() throws Exception {
        var storage = new DocumentStorage(root.toString());
        UUID id = UUID.randomUUID();
        byte[] pdf = "%PDF-1.4".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        Files.write(root.resolve(id + ".pdf"), pdf);
        assertThat(storage.read(id).getContentAsByteArray()).isEqualTo(pdf);
    }

    @Test
    void rejectsCleanupPathsOutsideStorage() {
        var storage = new DocumentStorage(root.toString());
        assertThatThrownBy(() -> storage.delete("../other.pdf")).isInstanceOf(IllegalArgumentException.class);
    }
}
