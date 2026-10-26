package alpha.business.insight.core.qa.documents;

import static org.assertj.core.api.Assertions.assertThat;

import alpha.business.insight.core.qa.helpers.ConcurrentRequests;
import alpha.business.insight.core.qa.helpers.SyntheticPdf;
import alpha.business.insight.core.qa.support.RealStackE2eTest;
import alpha.business.insight.core.service.DocumentStorage;
import io.qameta.allure.Allure;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import io.restassured.response.Response;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Проверяет независимость файлов, метаданных и точек Qdrant при параллельной HTTP-загрузке. */
@Epic("Backend")
@Feature("Конкурентная загрузка PDF")
@Tag("e2e")
@Tag("concurrency")
@Tag("documents")
class DocumentConcurrencyHttpE2eTest extends RealStackE2eTest {
    private static final String COLLECTION = "knowledge_documents_stub_v1";
    private static final long WAIT_SECONDS = 25;

    private final ConcurrentLinkedQueue<UUID> uploadedIds = new ConcurrentLinkedQueue<>();
    private String titlePrefix;

    @Autowired private DocumentStorage storage;
    @Autowired private ObjectMapper mapper;

    /** Загружает разные текстовые PDF одновременно через живой API с локальной ML-заглушкой. */
    @Test
    @DisplayName("TestE2EConcurrentPdfUploadsKeepArtifactsIndependent")
    void TestE2EConcurrentPdfUploadsKeepArtifactsIndependent() throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        titlePrefix = "Concurrent report " + suffix + " ";
        List<SyntheticPdf.MultipartUpload> uploads =
                List.of(
                        upload(
                                "report-a-" + suffix + ".pdf",
                                "Report A " + suffix,
                                "Revenue rose by 12 percent."),
                        upload(
                                "report-b-" + suffix + ".pdf",
                                "Report B " + suffix,
                                "Costs fell by 7 percent."),
                        upload(
                                "report-c-" + suffix + ".pdf",
                                "Report C " + suffix,
                                "Output reached 900 units."));
        String token =
                Allure.step(
                        "1. Получить JWT администратора и подготовить три разных PDF",
                        () -> {
                            String adminToken = keycloak.accessToken("sysadmin", "sysadmin-dev");
                            assertThat(uploads).hasSize(3);
                            assertThat(
                                            uploads.stream()
                                                    .map(SyntheticPdf.MultipartUpload::pdf)
                                                    .toList())
                                    .doesNotHaveDuplicates();
                            return adminToken;
                        });
        List<UploadResponse> responses =
                Allure.step(
                        "2. Одновременно отправить multipart-загрузки через HTTP",
                        () -> {
                            String parentStepUuid =
                                    Allure.getLifecycle().getCurrentTestCaseOrStep().orElseThrow();
                            try (var requests =
                                    ConcurrentRequests.startTogether(
                                            uploads.size(),
                                            parentStepUuid,
                                            java.time.Duration.ofSeconds(WAIT_SECONDS),
                                            index -> {
                                                SyntheticPdf.MultipartUpload upload =
                                                        uploads.get(index);
                                                Response response =
                                                        backend.postMultipart(
                                                                "/admin/documents",
                                                                upload,
                                                                token,
                                                                uploadTitle(index, suffix));
                                                UUID id = null;
                                                if (response.statusCode() == 201) {
                                                    try {
                                                        id =
                                                                UUID.fromString(
                                                                        json(response)
                                                                                .path("documentId")
                                                                                .asText());
                                                        uploadedIds.add(id);
                                                    } catch (Exception failure) {
                                                        throw new IllegalStateException(
                                                                "Успешная загрузка не вернула UUID документа",
                                                                failure);
                                                    }
                                                }
                                                return new UploadResponse(
                                                        index,
                                                        response.statusCode(),
                                                        response.asString(),
                                                        id);
                                            })) {
                                requests.awaitReady();
                                requests.release();
                                return requests.awaitResults();
                            }
                        });

        Allure.step(
                "3. Проверить собственные UUID, названия, метаданные и скачанные байты",
                () -> {
                    assertThat(responses).hasSize(uploads.size());
                    assertThat(responses).extracting(UploadResponse::status).containsOnly(201);
                    assertThat(responses)
                            .extracting(UploadResponse::documentId)
                            .doesNotHaveDuplicates();
                    for (UploadResponse uploaded : responses) {
                        SyntheticPdf.MultipartUpload input = uploads.get(uploaded.index());
                        var metadata = parseJson(uploaded.body());
                        assertThat(metadata.path("documentId").asText())
                                .isEqualTo(uploaded.documentId().toString());
                        assertThat(metadata.path("title").asText())
                                .isEqualTo(uploadTitle(uploaded.index(), suffix));
                        assertThat(metadata.path("fileName").asText()).isEqualTo(input.filename());
                        assertThat(metadata.path("sizeBytes").asLong())
                                .isEqualTo(input.pdf().length);
                        assertThat(metadata.path("sha256").asText()).isEqualTo(sha256(input.pdf()));
                        assertThat(metadata.path("chunkCount").asInt()).isEqualTo(1);

                        Map<String, Object> persisted =
                                jdbc.queryForMap(
                                        "SELECT title, file_name, size_bytes, sha256 "
                                                + "FROM documents WHERE id = ?",
                                        uploaded.documentId());
                        assertThat(persisted.get("title"))
                                .isEqualTo(uploadTitle(uploaded.index(), suffix));
                        assertThat(persisted.get("file_name")).isEqualTo(input.filename());
                        assertThat(((Number) persisted.get("size_bytes")).longValue())
                                .isEqualTo(input.pdf().length);
                        assertThat(persisted.get("sha256")).isEqualTo(sha256(input.pdf()));

                        Response details =
                                backend.get("/documents/" + uploaded.documentId(), token);
                        assertThat(details.statusCode()).isEqualTo(200);
                        assertThat(json(details).path("documentId").asText())
                                .isEqualTo(uploaded.documentId().toString());

                        Response download =
                                backend.getBytes(
                                        "/documents/" + uploaded.documentId() + "/file", token);
                        assertThat(download.statusCode()).isEqualTo(200);
                        assertThat(download.asByteArray()).containsExactly(input.pdf());
                    }
                });

        Allure.step(
                "4. Проверить, что каждая точка Qdrant принадлежит своему документу",
                () -> {
                    for (UploadResponse uploaded : responses) {
                        UUID expectedPointId = chunkId(uploaded.documentId());
                        Response pointResponse =
                                backend.getExternal(
                                        qdrantPointUri(expectedPointId),
                                        "точка конкурентной загрузки");
                        assertThat(pointResponse.statusCode()).isEqualTo(200);
                        var point = json(pointResponse).path("result");
                        assertThat(point.path("payload").path("documentId").asText())
                                .isEqualTo(uploaded.documentId().toString());
                        assertThat(point.path("payload").path("ordinal").isInt()).isTrue();
                        assertThat(point.path("payload").path("ordinal").asInt()).isZero();
                        assertThat(point.path("payload").path("stub").asBoolean()).isTrue();
                        assertThat(point.path("payload").path("accessScope").asText())
                                .isEqualTo("APPLICATION");
                        assertThat(point.path("vector").isArray()).isTrue();
                        var vector = point.path("vector");
                        assertThat(vector.size()).isEqualTo(8);
                        for (var component : vector) {
                            assertThat(component.isNumber()).isTrue();
                            assertThat(Double.isFinite(component.asDouble())).isTrue();
                        }
                        assertThat(vector).anyMatch(component -> component.asDouble() != 0.0);

                        var scroll =
                                backend.postExternalJson(
                                        URI.create(
                                                "http://"
                                                        + QDRANT.getHost()
                                                        + ":"
                                                        + QDRANT.getMappedPort(6333)
                                                        + "/collections/"
                                                        + COLLECTION
                                                        + "/points/scroll"),
                                        "все точки документа",
                                        "{\"filter\":{\"must\":[{\"key\":\"documentId\",\"match\":{\"value\":\""
                                                + uploaded.documentId()
                                                + "\"}}]},\"limit\":10,\"with_payload\":true}");
                        assertThat(scroll.statusCode()).isEqualTo(200);
                        var points = json(scroll).path("result").path("points");
                        assertThat(points.size()).isEqualTo(1);
                        assertThat(points.get(0).path("id").asText())
                                .isEqualTo(expectedPointId.toString());
                    }
                    assertThat(uploadedIds).hasSize(uploads.size());
                    Long matchingRows =
                            jdbc.queryForObject(
                                    "SELECT count(*) FROM documents WHERE title LIKE ?",
                                    Long.class,
                                    titlePrefix + "%");
                    assertThat(matchingRows).isEqualTo(uploads.size());
                });
    }

    /** Удаляет только документы, файлы и точки, записанные этим сценарием. */
    @AfterEach
    void removeConcurrentUploadArtifacts() throws Exception {
        LinkedHashSet<UUID> cleanupIds = new LinkedHashSet<>(uploadedIds);
        if (titlePrefix != null) {
            cleanupIds.addAll(
                    jdbc.query(
                            "SELECT id FROM documents WHERE title LIKE ?",
                            (result, row) -> result.getObject(1, UUID.class),
                            titlePrefix + "%"));
        }
        Exception cleanupFailure = null;
        for (UUID documentId : cleanupIds) {
            try {
                Response delete =
                        backend.postExternalJson(
                                URI.create(
                                        "http://"
                                                + QDRANT.getHost()
                                                + ":"
                                                + QDRANT.getMappedPort(6333)
                                                + "/collections/"
                                                + COLLECTION
                                                + "/points/delete?wait=true"),
                                "удаление тестовой точки",
                                "{\"filter\":{\"must\":[{\"key\":\"documentId\",\"match\":{\"value\":\""
                                        + documentId
                                        + "\"}}]}}");
                if (delete.statusCode() != 200) {
                    throw new IllegalStateException(
                            "Qdrant не удалил точки документа "
                                    + documentId
                                    + ": HTTP "
                                    + delete.statusCode());
                }
            } catch (Exception failure) {
                if (cleanupFailure == null) cleanupFailure = failure;
                else cleanupFailure.addSuppressed(failure);
            } finally {
                try {
                    List<String> paths =
                            jdbc.query(
                                    "SELECT file_path FROM documents WHERE id = ?",
                                    (result, row) -> result.getString(1),
                                    documentId);
                    jdbc.update("DELETE FROM documents WHERE id = ?", documentId);
                    for (String path : paths) storage.delete(path);
                    removeEmptyDocumentStorageDirectories(documentId);
                } catch (Exception failure) {
                    if (cleanupFailure == null) cleanupFailure = failure;
                    else cleanupFailure.addSuppressed(failure);
                }
            }
        }
        uploadedIds.clear();
        if (cleanupFailure != null) throw cleanupFailure;
    }

    /** Создаёт небольшую multipart-фикстуру, отличимую по байтам от параллельных запросов. */
    private static SyntheticPdf.MultipartUpload upload(String filename, String title, String text) {
        byte[] pdf = SyntheticPdf.textDocument(List.of(text));
        return new SyntheticPdf.MultipartUpload(
                filename,
                "application/pdf",
                pdf,
                "2026-10-24",
                "Параллельная HTTP-загрузка: " + title);
    }

    /** Возвращает устойчивое название конкретного параллельного запроса. */
    private static String uploadTitle(int index, String suffix) {
        return "Concurrent report " + suffix + " " + (char) ('A' + index);
    }

    /** Возвращает URI для чтения одной точки Qdrant с вектором и payload. */
    private static URI qdrantPointUri(UUID pointId) {
        return URI.create(
                "http://"
                        + QDRANT.getHost()
                        + ":"
                        + QDRANT.getMappedPort(6333)
                        + "/collections/"
                        + COLLECTION
                        + "/points/"
                        + pointId
                        + "?with_payload=true&with_vector=true");
    }

    /** Вычисляет детерминированный UUID точки по документу и ordinal 0. */
    private static UUID chunkId(UUID documentId) {
        return UUID.nameUUIDFromBytes((documentId + ":0").getBytes(StandardCharsets.UTF_8));
    }

    /** Вычисляет SHA-256 исходного PDF. */
    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new AssertionError(impossible);
        }
    }

    /** Разбирает JSON-ответ, собранный параллельным worker-потоком. */
    private JsonNode parseJson(String body) {
        try {
            return mapper.readTree(body);
        } catch (Exception failure) {
            throw new IllegalStateException("Ответ загрузки не является корректным JSON", failure);
        }
    }

    private record UploadResponse(int index, int status, String body, UUID documentId) {}
}
