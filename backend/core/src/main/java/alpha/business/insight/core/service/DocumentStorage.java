package alpha.business.insight.core.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.UUID;

/**
 * Хранит оригиналы PDF в каталогах по первым шести символам UUID документа.
 */
@Service
public class DocumentStorage {
    /**
     * Нормализованный абсолютный корень файлового хранилища.
     */
    private final Path root;

    /**
     * Принимает каталог оригиналов; сам каталог создаётся только при записи файла.
     */
    public DocumentStorage(@Value("${documents.storage-path:./data/documents}") String root) {
        this.root = Path.of(root).toAbsolutePath().normalize();
    }

    /**
     * Формирует путь вида ab/cd/ef/abcdef12-3456-7890-abcd-ef1234567890.pdf.
     *
     * @param documentId UUID документа
     * @return относительный путь с тремя уровнями каталогов
     */
    public String relativePath(UUID documentId) {
        String id = documentId.toString();
        return id.substring(0, 2) + "/" + id.substring(2, 4) + "/" + id.substring(4, 6) + "/" + id + ".pdf";
    }

    /**
     * Создаёт родительские каталоги и сохраняет PDF без перезаписи существующего файла.
     *
     * @param id идентификатор документа
     * @param pdf исходные байты PDF
     * @throws IOException если каталог или файл нельзя создать
     */
    public void write(UUID id, byte[] pdf) throws IOException {
        Path path = resolve(relativePath(id));
        Files.createDirectories(path.getParent());
        Files.write(path, pdf, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
    }

    /**
     * Возвращает ресурс оригинала; поддерживает прежние файлы в плоском каталоге.
     *
     * @param id идентификатор документа
     * @return ресурс файла; доступность ресурса проверяет вызывающий контроллер
     */
    public Resource read(UUID id) {
        Path path = resolve(relativePath(id));
        return new FileSystemResource(Files.exists(path) ? path : resolve(id + ".pdf"));
    }

    /**
     * Удаляет указанный оригинал, если он существует; повторный вызов безопасен.
     *
     * @param fileName относительный путь из задания очистки
     * @throws IOException если файл нельзя удалить
     */
    public void delete(String fileName) throws IOException {
        Files.deleteIfExists(resolve(fileName));
    }

    /**
     * Разрешает относительный путь внутри корня и отклоняет выход за его пределы.
     */
    private Path resolve(String fileName) {
        Path path = root.resolve(fileName).normalize();
        if (!path.startsWith(root) || path.equals(root)) {
            throw new IllegalArgumentException("Путь документа выходит за пределы хранилища");
        }
        return path;
    }
}
