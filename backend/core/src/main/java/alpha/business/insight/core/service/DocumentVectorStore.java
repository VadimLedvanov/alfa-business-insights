package alpha.business.insight.core.service;

import alpha.business.insight.core.ml.DocumentAnalysis;
import alpha.business.insight.core.ml.ComponentUnavailableException;
import alpha.business.insight.core.dto.InsightFragment;
import alpha.business.insight.core.ml.DocumentAnalyzer.Embedding;
import io.qdrant.client.grpc.Points.SearchPoints;
import io.qdrant.client.grpc.Points.WithPayloadSelector;
import io.qdrant.client.QdrantClient;
import io.qdrant.client.grpc.Collections.Distance;
import io.qdrant.client.grpc.Collections.VectorParams;
import io.qdrant.client.grpc.Common.Filter;
import io.qdrant.client.grpc.Points.PointStruct;
import io.qdrant.client.grpc.Points.UpdateStatus;
import io.qdrant.client.grpc.Points.UpdateResult;
import io.qdrant.client.grpc.Points.ScoredPoint;
import io.qdrant.client.grpc.JsonWithInt.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.util.UUID;
import java.util.List;
import java.util.Map;
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

    /** Фиксированная коллекция векторов документов, загружаемых администратором. */
    public static final String DOCUMENT_COLLECTION = "rag_documents";

    /**
     * gRPC-клиент Qdrant; подтверждение его операций ожидается синхронно.
     */
    private final QdrantClient client;
    private final float minScore;

    /**
     * Подключает клиент Qdrant, управляемый конфигурацией приложения.
     */
    public DocumentVectorStore(QdrantClient client,
                               @org.springframework.beans.factory.annotation.Value("${qdrant.min-score:0.65}")
                               float minScore) {
        if (!Float.isFinite(minScore) || minScore < 0 || minScore > 1) {
            throw new IllegalArgumentException("qdrant.min-score должен быть в диапазоне от 0 до 1");
        }
        this.client = client;
        this.minScore = minScore;
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
        if (analysis.embeddingModel() == null || analysis.embeddingDimension() < 1
                || (analysis.stub() && (!analysis.embeddingModel().equals("stub-v1") || analysis.embeddingDimension() != 8))) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Некорректные метаданные embedder в анализе PDF");
        }
        deadline = Math.min(deadline, System.nanoTime() + Duration.ofSeconds(5).toNanos());
        String collection = collectionName(analysis.embeddingModel(), analysis.embeddingDimension(), analysis.stub());
        ensureCollection(collection, analysis.embeddingDimension(), deadline);
        List<PointStruct> points = analysis.chunks().stream().map(chunk -> {
            PointStruct.Builder point = PointStruct.newBuilder()
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
        UpdateResult result = await(client.upsertAsync(collection, points, remaining(deadline)), deadline);
        if (result.getStatus() != UpdateStatus.Completed) {
            throw new ComponentUnavailableException("Qdrant");
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

    /** Возвращает фиксированную коллекцию админских документов или отдельную legacy-коллекцию stub. */
    public static String collectionName(String model, int dimension, boolean stub) {
        if (model == null || model.isBlank() || dimension < 1) {
            throw new IllegalArgumentException("Invalid embedding collection metadata");
        }
        if (stub) {
            if (!"stub-v1".equals(model) || dimension != 8) {
                throw new IllegalArgumentException("Invalid stub embedding collection metadata");
            }
            return STUB_COLLECTION;
        }
        return DOCUMENT_COLLECTION;
    }

    /** Ищет только в коллекции совместимой модели и размерности. */
    public List<InsightFragment> search(Embedding embedding) {
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        String collection = collectionName(embedding.embeddingModel(), embedding.embeddingDimension(), embedding.stub());
        if (!await(client.collectionExistsAsync(collection, remaining(deadline)), deadline)) {
            return List.of();
        }
        Filter filter = Filter.newBuilder()
                .addMust(matchKeyword("accessScope", "APPLICATION"))
                .addMust(matchKeyword("embeddingModel", embedding.embeddingModel())).build();
        List<ScoredPoint> points = await(client.searchAsync(SearchPoints.newBuilder().setCollectionName(collection)
                .addAllVector(embedding.embedding()).setLimit(5).setFilter(filter).setScoreThreshold(minScore)
                .setWithPayload(WithPayloadSelector.newBuilder().setEnable(true)).build(), remaining(deadline)), deadline);
        return points.stream().map(point -> {
            Map<String, Value> payload = point.getPayloadMap();
            try {
                return new InsightFragment(UUID.fromString(point.getId().getUuid()),
                        UUID.fromString(payload.get("documentId").getStringValue()),
                        Math.toIntExact(payload.get("page").getIntegerValue()), payload.get("text").getStringValue(),
                        payload.get("materialType").getStringValue(),
                        !payload.containsKey("stub") || payload.get("stub").getBoolValue());
            } catch (RuntimeException exception) {
                throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Некорректный фрагмент в Qdrant", exception);
            }
        }).toList();
    }

    /**
     * Создаёт коллекцию при первом обращении и проверяет размерность и метрику без пересоздания.
     */
    private void ensureCollection(String collection, int dimension, long deadline) {
        if (!await(client.collectionExistsAsync(collection, remaining(deadline)), deadline)) {
            try {
                await(client.createCollectionAsync(collection,
                        VectorParams.newBuilder().setSize(dimension).setDistance(Distance.Cosine).build(),
                        remaining(deadline)), deadline);
            } catch (ResponseStatusException exception) {
                // Другой запрос или экземпляр приложения мог одновременно создать коллекцию.
                if (!await(client.collectionExistsAsync(collection, remaining(deadline)), deadline)) {
                    throw exception;
                }
            }
        }
        VectorParams params = await(client.getCollectionInfoAsync(collection, remaining(deadline)), deadline)
                .getConfig().getParams().getVectorsConfig().getParams();
        if (params.getSize() != dimension || params.getDistance() != Distance.Cosine) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Несовместимая конфигурация коллекции Qdrant");
        }
    }

    /**
     * Удаляет все точки загрузки по payload.documentId, включая возможную частичную запись.
     *
     * @param documentId идентификатор загрузки
     * @throws ResponseStatusException если Qdrant не подтвердил очистку
     */
    public void delete(UUID documentId, String model, int dimension, boolean stub) {
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        String collection = collectionName(model, dimension, stub);
        if (!await(client.collectionExistsAsync(collection, remaining(deadline)), deadline)) {
            return;
        }
        Filter filter = Filter.newBuilder().addMust(matchKeyword("documentId", documentId.toString())).build();
        UpdateResult result = await(client.deleteAsync(collection, filter, remaining(deadline)), deadline);
        if (result.getStatus() != UpdateStatus.Completed) {
            throw new ComponentUnavailableException("Qdrant");
        }
    }

    /**
     * Возвращает оставшееся время или завершает операцию ошибкой HTTP 503.
     */
    private static Duration remaining(long deadline) {
        long nanos = deadline - System.nanoTime();
        if (nanos <= 0) {
            throw new ComponentUnavailableException("Qdrant");
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
            throw new ComponentUnavailableException("Qdrant");
        } catch (ExecutionException | TimeoutException exception) {
            future.cancel(true);
            throw new ComponentUnavailableException("Qdrant");
        } finally {
            if (!future.isDone()) {
                future.cancel(true);
            }
        }
    }
}
