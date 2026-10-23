package alpha.business.insight.core.service;

import alpha.business.insight.core.ml.DocumentAnalysis;
import io.qdrant.client.QdrantClient;
import io.qdrant.client.grpc.Collections.Distance;
import io.qdrant.client.grpc.Collections.VectorParams;
import io.qdrant.client.grpc.Common.Filter;
import io.qdrant.client.grpc.Points.PointStruct;
import io.qdrant.client.grpc.Points.UpdateStatus;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static io.qdrant.client.ConditionFactory.matchKeyword;
import static io.qdrant.client.PointIdFactory.id;
import static io.qdrant.client.ValueFactory.value;
import static io.qdrant.client.VectorsFactory.vectors;

/**
 * Индексирует векторы фрагментов и удаляет точки неудавшихся загрузок в Qdrant.
 */
@Service
public class DocumentVectorStore {
    /**
     * Изолированная коллекция синтетических векторов stub-v1 размерности 8.
     */
    public static final String STUB_COLLECTION = "knowledge_documents_stub_v1";

    /**
     * gRPC-клиент Qdrant; подтверждение его операций ожидается синхронно.
     */
    private final QdrantClient client;

    /**
     * Подключает клиент Qdrant, управляемый конфигурацией приложения.
     */
    public DocumentVectorStore(QdrantClient client) {
        this.client = client;
    }

    /**
     * Записывает чанки с текстами, страницами и векторами и дожидается статуса Completed.
     *
     * @param analysis проверенный ответ ML
     * @param publicationDate дата публикации либо null
     * @param deadline предельное время обработки в шкале System.nanoTime()
     * @throws ResponseStatusException при несовместимой модели, ошибке Qdrant или истечении времени
     */
    public void index(DocumentAnalysis analysis, LocalDate publicationDate, long deadline) {
        if (!analysis.stub() || !analysis.embeddingModel().equals("stub-v1") || analysis.embeddingDimension() != 8) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "Для этой версии настроена только изолированная коллекция stub-v1");
        }
        ensureCollection(deadline);
        var points = analysis.chunks().stream().map(chunk -> {
            var point = PointStruct.newBuilder()
                    .setId(id(chunkId(analysis.documentId(), chunk.ordinal())))
                    .setVectors(vectors(chunk.embedding()))
                    .putPayload("documentId", value(analysis.documentId().toString()))
                    .putPayload("text", value(chunk.text()))
                    .putPayload("page", value(chunk.page()))
                    .putPayload("ordinal", value(chunk.ordinal()))
                    .putPayload("materialType", value("BANK_ANALYTICS"))
                    .putPayload("accessScope", value("APPLICATION"))
                    .putPayload("embeddingModel", value(analysis.embeddingModel()))
                    .putPayload("stub", value(analysis.stub()));
            if (publicationDate != null) {
                point.putPayload("publicationDate", value(publicationDate.toString()));
            }
            return point.build();
        }).toList();
        var result = await(client.upsertAsync(STUB_COLLECTION, points, remaining(deadline)), deadline);
        if (result.getStatus() != UpdateStatus.Completed) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Qdrant не подтвердил индексацию");
        }
    }

    /**
     * Назначает чанку стабильный UUID по документу и порядковому номеру.
     *
     * @param documentId идентификатор документа из PostgreSQL
     * @param ordinal номер чанка внутри документа
     * @return ID точки Qdrant, воспроизводимый для той же пары значений
     */
    public static UUID chunkId(UUID documentId, int ordinal) {
        return UUID.nameUUIDFromBytes((documentId + ":" + ordinal).getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Создаёт коллекцию при первом обращении и проверяет размерность и метрику без пересоздания.
     */
    private void ensureCollection(long deadline) {
        if (!await(client.collectionExistsAsync(STUB_COLLECTION, remaining(deadline)), deadline)) {
            try {
                await(client.createCollectionAsync(STUB_COLLECTION,
                        VectorParams.newBuilder().setSize(8).setDistance(Distance.Cosine).build(),
                        remaining(deadline)), deadline);
            } catch (ResponseStatusException exception) {
                // Другой запрос или экземпляр приложения мог одновременно создать коллекцию.
                if (!await(client.collectionExistsAsync(STUB_COLLECTION, remaining(deadline)), deadline)) {
                    throw exception;
                }
            }
        }
        var params = await(client.getCollectionInfoAsync(STUB_COLLECTION, remaining(deadline)), deadline)
                .getConfig().getParams().getVectorsConfig().getParams();
        if (params.getSize() != 8 || params.getDistance() != Distance.Cosine) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Несовместимая конфигурация коллекции Qdrant");
        }
    }

    /**
     * Удаляет все точки загрузки по payload.documentId, включая возможную частичную запись.
     *
     * @param documentId идентификатор загрузки
     * @throws ResponseStatusException если Qdrant не подтвердил очистку
     */
    public void delete(UUID documentId) {
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        if (!await(client.collectionExistsAsync(STUB_COLLECTION, remaining(deadline)), deadline)) {
            return;
        }
        Filter filter = Filter.newBuilder().addMust(matchKeyword("documentId", documentId.toString())).build();
        var result = await(client.deleteAsync(STUB_COLLECTION, filter, remaining(deadline)), deadline);
        if (result.getStatus() != UpdateStatus.Completed) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Qdrant не подтвердил очистку");
        }
    }

    /**
     * Возвращает оставшееся время или завершает операцию ошибкой HTTP 503.
     */
    private static Duration remaining(long deadline) {
        long nanos = deadline - System.nanoTime();
        if (nanos <= 0) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Истекло время обращения к Qdrant");
        }
        return Duration.ofNanos(nanos);
    }

    /**
     * Синхронно ожидает ответ gRPC в пределах общего срока и отменяет незавершённый вызов.
     */
    private static <T> T await(Future<T> future, long deadline) {
        try {
            return future.get(remaining(deadline).toNanos(), TimeUnit.NANOSECONDS);
        } catch (InterruptedException exception) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Обращение к Qdrant прервано", exception);
        } catch (ExecutionException | TimeoutException exception) {
            future.cancel(true);
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Qdrant недоступен", exception);
        } finally {
            if (!future.isDone()) {
                future.cancel(true);
            }
        }
    }
}
