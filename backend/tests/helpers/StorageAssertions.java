package alpha.business.insight.core.qa.helpers;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/** Проверки наблюдаемого состояния файловой системы в тестах загрузки документов. */
public final class StorageAssertions {
    /** Запрещает создавать экземпляры класса со статическими проверками. */
    private StorageAssertions() { }

    /** Проверяет отсутствие файлов после очистки, допуская пустые каталоги сегментов. */
    public static void assertNoRegularFiles(Path root) throws IOException {
        try (var paths = Files.walk(root)) {
            assertThat(paths.filter(Files::isRegularFile).toList()).isEmpty();
        }
    }
}
