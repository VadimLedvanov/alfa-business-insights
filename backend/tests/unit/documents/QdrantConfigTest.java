package alpha.business.insight.core.document;

import alpha.business.insight.core.config.QdrantConfig;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import io.qameta.allure.Step;

import java.net.InetAddress;
import java.net.ServerSocket;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

/** Проверяет, что создание клиента Qdrant не блокируется сетевым ожиданием. */
@Tag("unit")
@Tag("documents")
class QdrantConfigTest {
    /** Создаёт клиент без ожидания ответа Qdrant. */
    @Test
    @Step("Создаёт клиент без ожидания ответа Qdrant")
    void clientCreationDoesNotBlockOnUnresponsiveQdrant() throws Exception {
        try (var server = new ServerSocket(0, 50, InetAddress.getLoopbackAddress())) {
            assertTimeoutPreemptively(Duration.ofSeconds(2), () -> {
                try (var ignored = new QdrantConfig().qdrantClient("127.0.0.1", server.getLocalPort())) {
                    // Создание клиента не должно выполнять сетевые запросы без таймаута.
                }
            });
        }
    }
}
