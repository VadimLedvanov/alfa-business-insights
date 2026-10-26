package alpha.business.insight.core.config;

import java.time.Duration;

import io.qdrant.client.QdrantClient;
import io.qdrant.client.QdrantGrpcClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class QdrantConfig {

    @Bean(destroyMethod = "close")
    public QdrantClient qdrantClient(
            @Value("${qdrant.host}") String host,
            @Value("${qdrant.grpc-port}") int grpcPort) {
        return new QdrantClient(QdrantGrpcClient.newBuilder(host, grpcPort, false, false).withTimeout(Duration.ofSeconds(5)).build());
    }
}
