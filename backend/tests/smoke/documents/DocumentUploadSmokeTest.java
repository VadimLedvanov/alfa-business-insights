package alpha.business.insight.core.qa.documents;

import static org.assertj.core.api.Assertions.assertThat;

import alpha.business.insight.core.qa.helpers.SyntheticPdf;
import alpha.business.insight.core.qa.support.RealStackE2eTest;
import alpha.business.insight.core.service.DocumentStorage;
import io.qameta.allure.Allure;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import io.qameta.allure.Story;
import io.restassured.response.Response;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** Проверяет сквозную загрузку, чтение и удаление синтетического документа. */
@Epic("Backend")
@Feature("Документы аналитики банка")
@Tag("e2e")
@Tag("smoke")
@Tag("documents")
class DocumentUploadSmokeTest extends RealStackE2eTest {
    private static final String COLLECTION = "knowledge_documents_stub_v1";
    private final SyntheticPdf.MultipartUpload uploadFixture = SyntheticPdf.multipartUpload();

    @Autowired DocumentStorage storage;

    private UUID documentId;

    /** Выполняет полный сценарий загрузки, проверки прав и скачивания PDF. */
    @Test
    @Tag("e2e")
    @Story("Сохранить PDF, метаданные и синтетический вектор, затем скачать оригинал")
    @DisplayName("TestSmokePdfUploadRoundTrip")
    void TestSmokePdfUploadRoundTrip() throws Exception {
        Allure.step(
                "Проверить, что загружать PDF может только системный администратор",
                () -> {
                    assertThat(uploadPdf(null).statusCode()).isEqualTo(401);
                    assertThat(
                                    uploadPdf(keycloak.accessToken("director", "director-dev"))
                                            .statusCode())
                            .isEqualTo(403);
                });

        Allure.step(
                "Загрузить PDF и проверить метаданные и явные признаки ML-заглушки",
                () -> {
                    // Загрузить PDF от имени системного администратора.
                    Response uploaded = uploadPdf(keycloak.accessToken("sysadmin", "sysadmin-dev"));
                    assertThat(uploaded.statusCode()).isEqualTo(201);
                    var metadata = json(uploaded);
                    documentId = UUID.fromString(metadata.path("documentId").asText());

                    // Сверить метаданные и явные признаки тестовой заглушки.
                    assertThat(metadata.path("title").asText()).isEqualTo("E2E synthetic report");
                    assertThat(metadata.path("chunkCount").asInt()).isEqualTo(1);
                    assertThat(metadata.path("stub").asBoolean()).isTrue();
                    assertThat(metadata.path("embeddingModel").asText()).isEqualTo("stub-v1");
                    assertThat(metadata.path("embeddingDimension").asInt()).isEqualTo(8);
                });

        Allure.step(
                "Проверить, что директор читает метаданные документа",
                () -> {
                    String directorToken = keycloak.accessToken("director", "director-dev");
                    // Запросить метаданные документа.
                    var details = backend.get("/documents/" + documentId, directorToken);

                    // Проверить статус и SHA-256 исходного файла.
                    assertThat(details.statusCode()).isEqualTo(200);
                    assertThat(json(details).path("sha256").asText()).hasSize(64);
                });

        Allure.step(
                "Скачать PDF и сверить его с исходным файлом",
                () -> {
                    String directorToken = keycloak.accessToken("director", "director-dev");
                    // Скачать исходный PDF.
                    var downloaded =
                            backend.getBytes("/documents/" + documentId + "/file", directorToken);

                    // Сверить тип содержимого и каждый байт.
                    assertThat(downloaded.statusCode()).isEqualTo(200);
                    assertThat(downloaded.getHeader("Content-Type")).contains("application/pdf");
                    assertThat(downloaded.asByteArray()).containsExactly(uploadFixture.pdf());
                });

        Allure.step(
                "Прочитать точку Qdrant и проверить вектор и payload",
                () -> {
                    var pointResponse =
                            backend.getExternal(
                                    URI.create(
                                            "http://"
                                                    + QDRANT.getHost()
                                                    + ":"
                                                    + QDRANT.getMappedPort(6333)
                                                    + "/collections/"
                                                    + COLLECTION
                                                    + "/points/"
                                                    + chunkId(documentId)
                                                    + "?with_payload=true&with_vector=true"),
                                    "Qdrant point payload");

                    // Сверить документ, текст-заглушку, область доступа и вектор.
                    assertThat(pointResponse.statusCode()).isEqualTo(200);
                    var point = json(pointResponse).path("result");
                    assertThat(point.path("payload").path("documentId").asText())
                            .isEqualTo(documentId.toString());
                    assertThat(point.path("payload").path("text").asText()).contains("[ML STUB]");
                    assertThat(point.path("payload").path("stub").asBoolean()).isTrue();
                    assertThat(point.path("payload").path("accessScope").asText())
                            .isEqualTo("APPLICATION");
                    assertThat(point.path("vector").isMissingNode()).isFalse();
                });
    }

    /** Удаляет точку Qdrant, строку БД и файл, созданные этим сценарием. */
    @AfterEach
    void removeUploadedArtifacts() throws Exception {
        if (documentId == null) return;
        String pointId = chunkId(documentId).toString();
        Allure.step(
                "Очистить точку Qdrant, метаданные и исходный файл",
                () -> {
                    try {
                        var response =
                                backend.postExternalJson(
                                        URI.create(
                                                "http://"
                                                        + QDRANT.getHost()
                                                        + ":"
                                                        + QDRANT.getMappedPort(6333)
                                                        + "/collections/"
                                                        + COLLECTION
                                                        + "/points/delete?wait=true"),
                                        "удаление тестовой точки после теста",
                                        "{\"points\":[\"" + pointId + "\"]}");
                        assertThat(response.statusCode()).isEqualTo(200);
                        var deletedPoint =
                                backend.getExternal(
                                        URI.create(
                                                "http://"
                                                        + QDRANT.getHost()
                                                        + ":"
                                                        + QDRANT.getMappedPort(6333)
                                                        + "/collections/"
                                                        + COLLECTION
                                                        + "/points/"
                                                        + pointId
                                                        + "?with_payload=true"),
                                        "подтверждение удаления тестовой точки");
                        assertThat(deletedPoint.statusCode()).isEqualTo(404);
                    } finally {
                        String relativePath =
                                jdbc.queryForObject(
                                        "SELECT file_path FROM documents WHERE id = ?",
                                        String.class,
                                        documentId);
                        jdbc.update("DELETE FROM documents WHERE id = ?", documentId);
                        storage.delete(relativePath);
                        removeEmptyDocumentStorageDirectories(documentId);
                    }
                });
    }

    /** Загружает подготовленный PDF через multipart endpoint backend. */
    private Response uploadPdf(String bearer) {
        return backend.postMultipart("/admin/documents", uploadFixture, bearer);
    }

    /** Вычисляет тот же детерминированный идентификатор точки, что и production индексатор. */
    private static UUID chunkId(UUID id) {
        return UUID.nameUUIDFromBytes((id + ":0").getBytes(StandardCharsets.UTF_8));
    }
}
