package alpha.business.insight.core.qa.events;

import static org.assertj.core.api.Assertions.assertThat;

import alpha.business.insight.core.qa.support.RealStackE2eTest;
import alpha.business.insight.core.service.NewsIngestionJob;
import io.qameta.allure.Allure;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.ImageFromDockerfile;
import org.testcontainers.utility.MountableFile;

/** Проверяет цепочку локальный RSS → Python → Java → БД → HTTP без внешней LLM. */
@Epic("Backend")
@Feature("События")
@Tag("e2e")
@Tag("events")
@Tag("ml")
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "ml.stub=false",
            "news.collect.enabled=true",
            "news.collect.initial-delay=PT24H",
            "news.collect.interval=PT24H"
        })
class NewsIngestionHttpE2eTest extends RealStackE2eTest {
    private static final String PREFIX = "SYNTHETIC-NEWS-" + UUID.randomUUID();
    private static final Path ROOT = projectRoot();
    private static final GenericContainer<?> ML =
            new GenericContainer<>(
                            new ImageFromDockerfile("alfa-business-insights-news-qa", false)
                                    .withFileFromPath(".", ROOT.resolve("ml")))
                    .withCopyFileToContainer(
                            MountableFile.forHostPath(
                                    ROOT.resolve("backend/tests/support/news/qa_news_app.py"),
                                    0644),
                            "/app/qa_news_app.py")
                    .withEnv("ML_EMBEDDER", "stub")
                    .withEnv("ML_STUB", "false")
                    .withEnv("QA_NEWS_PREFIX", PREFIX)
                    .withCommand(
                            "uvicorn", "qa_news_app:app", "--host", "0.0.0.0", "--port", "8000")
                    .withExposedPorts(8000)
                    .waitingFor(
                            Wait.forHttp("/health")
                                    .forPort(8000)
                                    .withStartupTimeout(Duration.ofMinutes(3)));

    static {
        ML.start();
    }

    @Autowired private NewsIngestionJob job;
    @Autowired private ApplicationContext context;

    /** Передаёт Feign адрес настоящего Python-сервиса с локальными источниками RSS. */
    @DynamicPropertySource
    static void mlProperties(DynamicPropertyRegistry properties) {
        properties.add("ml.url", () -> "http://" + ML.getHost() + ":" + ML.getMappedPort(8000));
    }

    /** Проверяет сохранение новости, отсутствие дублей и чтение директором при отказе одного RSS. */
    @Test
    void TestE2ENewsCollectionPersistsAndDeduplicates() throws Exception {
        Allure.step(
                "1. Проверить отключение модели и выполнить первый scheduled job вручную",
                () -> {
                    assertThat(context.getBeansOfType(ChatModel.class))
                            .as("Внешняя LLM отключена")
                            .isEmpty();
                    job.collect();
                });
        var rows =
                Allure.step(
                        "2. Проверить сохранённую новость и метаданные первоисточника",
                        () -> {
                            var saved =
                                    jdbc.queryForList(
                                            "SELECT id, title, source_ref, status, source_type, external_id, source_url, source_name, published_at, materials_version FROM events WHERE title LIKE ?",
                                            PREFIX + "%");
                            Allure.addAttachment(
                                    "Сохранённые события", "text/plain", saved.toString());
                            assertThat(saved)
                                    .as("Одна бизнес-новость; URL-дубль и спорт не сохраняются")
                                    .hasSize(1);
                            var row = saved.getFirst();
                            assertThat(row.get("status")).isEqualTo("READY");
                            assertThat(row.get("source_type")).isEqualTo("TEXT");
                            assertThat(row.get("external_id")).isNotNull();
                            assertThat(row.get("source_url"))
                                    .isEqualTo("http://127.0.0.1:8099/article");
                            assertThat(row.get("source_name")).isEqualTo("qa-local");
                            assertThat(row.get("published_at")).isNotNull();
                            assertThat(((Number) row.get("materials_version")).longValue())
                                    .isEqualTo(1L);
                            assertThat(row.get("source_ref").toString())
                                    .contains("corporate borrowing costs", "Источник:");
                            return saved;
                        });
        Allure.step(
                "3. Повторить сбор и убедиться, что сохранён тот же event ID",
                () -> {
                    job.collect();
                    var repeated =
                            jdbc.queryForList(
                                    "SELECT id FROM events WHERE title LIKE ?", PREFIX + "%");
                    assertThat(repeated).hasSize(1);
                    assertThat(repeated.getFirst().get("id")).isEqualTo(rows.getFirst().get("id"));
                });
        Allure.step(
                "4. Прочитать событие и список через настоящий JWT директора",
                () -> {
                    String token = keycloak.accessToken("director", "director-dev");
                    var read = backend.get("/events/" + rows.getFirst().get("id"), token);
                    assertThat(read.statusCode())
                            .as("GET event: %s", read.asString())
                            .isEqualTo(200);
                    assertThat(json(read).path("status").asText()).isEqualTo("READY");
                    assertThat(json(read).path("sourceRef").asText())
                            .contains("corporate borrowing costs");
                    var list =
                            backend.get(
                                    "/events?title=" + PREFIX + "&status=READY&size=100", token);
                    assertThat(list.statusCode())
                            .as("GET events: %s", list.asString())
                            .isEqualTo(200);
                    assertThat(json(list).path("content").isArray()).isTrue();
                    assertThat(json(list).path("totalElements").asInt()).isEqualTo(1);
                    assertThat(json(list).path("content").get(0).path("id").asText())
                            .isEqualTo(rows.getFirst().get("id").toString());
                });
    }

    /** Удаляет события данного сценария и сбрасывает временный JSONL для повторного запуска. */
    @AfterEach
    void cleanupNews() throws Exception {
        jdbc.update("DELETE FROM events WHERE title LIKE ?", PREFIX + "%");
        ML.execInContainer("rm", "-f", "/tmp/qa-news/news.jsonl");
    }

    /** Находит корень проекта независимо от рабочего каталога IDE и Maven. */
    private static Path projectRoot() {
        Path path = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        while (path != null) {
            if (Files.isRegularFile(path.resolve("ml/Dockerfile"))) return path;
            path = path.getParent();
        }
        throw new IllegalStateException("Не найден корень проекта с ml/Dockerfile");
    }
}
