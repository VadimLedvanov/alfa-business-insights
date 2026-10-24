package alpha.business.insight.core.qa.support;

import alpha.business.insight.core.qa.connectors.BackendClient;
import alpha.business.insight.core.qa.connectors.KeycloakClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.qameta.allure.Allure;
import java.io.IOException;
import java.net.URI;
import java.nio.file.DirectoryNotEmptyException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.extension.TestWatcher;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.lifecycle.Startables;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.MountableFile;

/** Базовый класс HTTP-тестов приложения с реальными инфраструктурными сервисами. */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "ml.stub=true",
            "spring.ai.model.chat=none",
            "spring.jpa.hibernate.ddl-auto=validate"
        })
public abstract class RealStackE2eTest {
    private static final String REALM = "alfa-business-insights";
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String STORAGE_ROOT =
            System.getProperty("java.io.tmpdir")
                    + "/alfa-business-insights-e2e-"
                    + UUID.randomUUID();

    protected static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18");

    protected static final GenericContainer<?> KEYCLOAK =
            new GenericContainer<>("quay.io/keycloak/keycloak:26.8.1")
                    .withExposedPorts(8080, 9000)
                    .withEnv("KC_BOOTSTRAP_ADMIN_USERNAME", "admin")
                    .withEnv("KC_BOOTSTRAP_ADMIN_PASSWORD", "admin-dev")
                    .withEnv("KC_HEALTH_ENABLED", "true")
                    .withCommand("start-dev", "--import-realm")
                    .withCopyFileToContainer(
                            MountableFile.forHostPath(realmImportFile().toString()),
                            "/opt/keycloak/data/import/alfa-business-insights-realm.json")
                    .waitingFor(
                            Wait.forHttp("/health/ready")
                                    .forPort(9000)
                                    .forStatusCode(200)
                                    .withStartupTimeout(Duration.ofMinutes(3)));

    protected static final GenericContainer<?> QDRANT =
            new GenericContainer<>("qdrant/qdrant:v1.19.0")
                    .withExposedPorts(6333, 6334)
                    .waitingFor(Wait.forHttp("/readyz").forPort(6333).forStatusCode(200));

    static {
        Startables.deepStart(Stream.of(POSTGRES, KEYCLOAK, QDRANT)).join();
    }

    private final List<String> httpExchanges = new ArrayList<>();

    protected BackendClient backend;
    protected KeycloakClient keycloak;

    @RegisterExtension
    protected final TestWatcher failedHttpExchangeAttachment =
            new TestWatcher() {
                /** Прикладывает собранные ответы к упавшему тесту. */
                @Override
                public void testFailed(ExtensionContext context, Throwable cause) {
                    if (!httpExchanges.isEmpty()) {
                        Allure.addAttachment(
                                "HTTP responses (redacted)",
                                "text/plain",
                                String.join("\n\n", httpExchanges));
                    }
                }
            };

    @Autowired protected JdbcTemplate jdbc;

    @LocalServerPort protected int serverPort;

    /** Создаёт Rest Assured-клиенты после выбора динамического порта приложения. */
    @BeforeEach
    void initializeHttpConnectors() {
        backend =
                new BackendClient(
                        URI.create("http://localhost:" + serverPort + "/api"), httpExchanges::add);
        keycloak = new KeycloakClient(URI.create(keycloakBaseUrl()), httpExchanges::add);
    }

    /** Подставляет адреса контейнерных зависимостей в Spring перед стартом контекста. */
    @DynamicPropertySource
    static void realInfrastructureProperties(DynamicPropertyRegistry properties) {
        String issuer = keycloakBaseUrl() + "/realms/" + REALM;
        properties.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        properties.add("spring.datasource.username", POSTGRES::getUsername);
        properties.add("spring.datasource.password", POSTGRES::getPassword);
        properties.add("spring.security.oauth2.resourceserver.jwt.issuer-uri", () -> issuer);
        properties.add(
                "spring.security.oauth2.resourceserver.jwt.jwk-set-uri",
                () -> issuer + "/protocol/openid-connect/certs");
        properties.add("spring.security.oauth2.resourceserver.jwt.audiences", () -> "alfa-backend");
        properties.add("qdrant.host", QDRANT::getHost);
        properties.add("qdrant.grpc-port", () -> QDRANT.getMappedPort(6334));
        properties.add("documents.storage-path", () -> STORAGE_ROOT);
    }

    /** Разбирает JSON-фактический ответ Rest Assured. */
    protected JsonNode json(io.restassured.response.Response response) throws Exception {
        return JSON.readTree(response.asString());
    }

    /** Возвращает URL запущенного тестового realm Keycloak. */
    protected static String keycloakBaseUrl() {
        return "http://" + KEYCLOAK.getHost() + ":" + KEYCLOAK.getMappedPort(8080);
    }

    /** Удаляет пустые каталоги хранения после удаления загруженного документа. */
    protected static void removeEmptyDocumentStorageDirectories(UUID documentId)
            throws IOException {
        Path root = Path.of(STORAGE_ROOT);
        String id = documentId.toString();
        Path directory =
                root.resolve(id.substring(0, 2))
                        .resolve(id.substring(2, 4))
                        .resolve(id.substring(4, 6));
        while (directory != null && directory.startsWith(root)) {
            try {
                Files.deleteIfExists(directory);
            } catch (DirectoryNotEmptyException sharedStorageStillInUse) {
                // Another test may have files below this shared parent; leave it and its contents
                // intact.
                break;
            }
            directory = directory.getParent();
        }
    }

    /** Находит realm import из корня рабочей копии. */
    private static Path realmImportFile() {
        Path directory = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        while (directory != null) {
            Path candidate =
                    directory.resolve("infrastructure/keycloak/alfa-business-insights-realm.json");
            if (Files.isRegularFile(candidate)) return candidate;
            directory = directory.getParent();
        }
        throw new IllegalStateException(
                "Cannot find imported Keycloak realm under the test working directory");
    }
}
