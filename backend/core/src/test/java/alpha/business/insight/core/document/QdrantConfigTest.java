package alpha.business.insight.core.document;

import alpha.business.insight.core.config.QdrantConfig;
import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.net.ServerSocket;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

class QdrantConfigTest {
    @Test
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
