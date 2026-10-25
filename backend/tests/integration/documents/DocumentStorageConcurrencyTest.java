package alpha.business.insight.core.document;

import static org.assertj.core.api.Assertions.assertThat;

import alpha.business.insight.core.qa.helpers.TestEvidence;
import alpha.business.insight.core.service.DocumentStorage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Проверяет конкурентную запись синтетических файлов в production-хранилище. */
@Tag("integration")
@Tag("documents")
@Tag("concurrency")
class DocumentStorageConcurrencyTest {
    private static final long TIMEOUT_SECONDS = 10;

    @TempDir Path root;

    /** Проверяет единственного победителя при записи одного UUID. */
    @Test
    void TestIntegrationConcurrentWritesKeepSingleWinner() throws Exception {
        var storage = new DocumentStorage(root.toString());
        UUID id = UUID.fromString("abcdef12-3456-7890-abcd-ef1234567890");
        byte[] first =
                "%PDF-1.4\nsynthetic-first".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] second =
                "%PDF-1.4\nsynthetic-second".getBytes(java.nio.charset.StandardCharsets.UTF_8);

        // Подготовка
        TestEvidence.step(
                "Одновременная запись одного UUID без перезаписи",
                List.of(
                        new TestEvidence.Input("documentId", id),
                        new TestEvidence.Input("firstPdfBytes", first),
                        new TestEvidence.Input("secondPdfBytes", second),
                        new TestEvidence.Input("scope", "filesystem integration; no HTTP or AI")),
                () -> {
                    // Действие
                    List<WriteResult> results =
                            writeTogether(
                                    storage,
                                    List.of(
                                            new WriteRequest(id, first),
                                            new WriteRequest(id, second)));
                    byte[] stored = Files.readAllBytes(root.resolve(storage.relativePath(id)));
                    long pdfCount = countPdfs();
                    return new WriteObservation(results, stored, pdfCount);
                },
                actual -> {
                    // Проверки
                    assertThat(actual.workers())
                            .extracting(WriteResult::outcome)
                            .containsExactlyInAnyOrder("written", "already-exists");
                    WriteResult winner =
                            actual.workers().stream()
                                    .filter(result -> result.outcome().equals("written"))
                                    .findFirst()
                                    .orElseThrow();
                    assertThat(actual.storedBytes())
                            .isEqualTo(winner.requestIndex() == 0 ? first : second);
                    assertThat(actual.pdfCount()).isEqualTo(1L);
                });
    }

    /** Проверяет отдельные полные файлы для UUID с общим каталогом. */
    @Test
    void TestIntegrationConcurrentDocumentsKeepOwnBytes() throws Exception {
        var storage = new DocumentStorage(root.toString());
        UUID firstId = UUID.fromString("abcdef01-1111-2222-3333-444444444444");
        UUID secondId = UUID.fromString("abcdef02-5555-6666-7777-888888888888");
        byte[] first =
                "%PDF-1.4\nsynthetic-document-one"
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] second =
                "%PDF-1.4\nsynthetic-document-two"
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8);

        // Подготовка
        TestEvidence.step(
                "Одновременная запись разных UUID в общий каталог",
                List.of(
                        new TestEvidence.Input("documentIds", List.of(firstId, secondId)),
                        new TestEvidence.Input("pdfBytes", List.of(first, second)),
                        new TestEvidence.Input("scope", "filesystem integration; no HTTP or AI")),
                () -> {
                    // Действие
                    List<WriteResult> results =
                            writeTogether(
                                    storage,
                                    List.of(
                                            new WriteRequest(firstId, first),
                                            new WriteRequest(secondId, second)));
                    byte[] firstStored =
                            Files.readAllBytes(root.resolve(storage.relativePath(firstId)));
                    byte[] secondStored =
                            Files.readAllBytes(root.resolve(storage.relativePath(secondId)));
                    return new DocumentsObservation(
                            results, firstStored, secondStored, countPdfs());
                },
                actual -> {
                    // Проверки
                    assertThat(actual.workers())
                            .extracting(WriteResult::outcome)
                            .containsOnly("written");
                    assertThat(actual.firstStored()).isEqualTo(first);
                    assertThat(actual.secondStored()).isEqualTo(second);
                    assertThat(actual.pdfCount()).isEqualTo(2L);
                });
    }

    /** Синхронно запускает две записи и собирает их фактические исходы. */
    private List<WriteResult> writeTogether(DocumentStorage storage, List<WriteRequest> requests)
            throws Exception {
        ExecutorService workers = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<WriteResult>> futures =
                    IntStream.range(0, requests.size())
                            .mapToObj(
                                    index ->
                                            workers.submit(
                                                    () -> {
                                                        WriteRequest request = requests.get(index);
                                                        ready.countDown();
                                                        if (!start.await(
                                                                TIMEOUT_SECONDS,
                                                                TimeUnit.SECONDS)) {
                                                            throw new IllegalStateException(
                                                                    "Worker did not receive start signal");
                                                        }
                                                        try {
                                                            storage.write(
                                                                    request.id(), request.bytes());
                                                            return new WriteResult(
                                                                    request.id(), index, "written");
                                                        } catch (
                                                                java.nio.file
                                                                                .FileAlreadyExistsException
                                                                        expected) {
                                                            return new WriteResult(
                                                                    request.id(),
                                                                    index,
                                                                    "already-exists");
                                                        }
                                                    }))
                            .toList();
            assertThat(ready.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            return List.of(
                    futures.get(0).get(TIMEOUT_SECONDS, TimeUnit.SECONDS),
                    futures.get(1).get(TIMEOUT_SECONDS, TimeUnit.SECONDS));
        } finally {
            start.countDown();
            workers.shutdownNow();
            assertThat(workers.awaitTermination(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue();
        }
    }

    /** Считает созданные PDF после завершения записей. */
    private long countPdfs() throws Exception {
        try (var paths = Files.walk(root)) {
            return paths.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".pdf"))
                    .count();
        }
    }

    /** Описывает запрос worker-потока. */
    private record WriteRequest(UUID id, byte[] bytes) {}

    /** Сохраняет фактический исход записи для отчёта главного потока. */
    private record WriteResult(UUID id, int requestIndex, String outcome) {}

    /** Сохраняет результат записи одного UUID для проверки главного потока. */
    private record WriteObservation(List<WriteResult> workers, byte[] storedBytes, long pdfCount) {}

    /** Сохраняет результаты записи двух UUID для проверки главного потока. */
    private record DocumentsObservation(
            List<WriteResult> workers, byte[] firstStored, byte[] secondStored, long pdfCount) {}
}
