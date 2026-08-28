package ma.onda.rag.agent.infra.springai;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.net.URI;
import java.time.Duration;

@ConfigurationProperties("rag.post-retrieval.reranking")
public record RerankingProperties(
        @DefaultValue("false") boolean enabled,
        @DefaultValue("https://api.cohere.com/v2/rerank") URI endpoint,
        @DefaultValue("") String apiKey,
        @DefaultValue("rerank-v3.5") String model,
        @DefaultValue("2s") Duration timeout,
        @DefaultValue("4") int maxConcurrentCalls,
        @DefaultValue("3") int failureThreshold,
        @DefaultValue("30s") Duration openDuration
) {
    public RerankingProperties {
        if (timeout == null || timeout.isNegative() || timeout.isZero()
                || openDuration == null || openDuration.isNegative() || openDuration.isZero()
                || maxConcurrentCalls < 1 || failureThreshold < 1) {
            throw new IllegalArgumentException("Reranking timeouts, concurrency and failure threshold must be positive");
        }
        if (endpoint == null || endpoint.getHost() == null
                || !("https".equals(endpoint.getScheme()) || "http".equals(endpoint.getScheme()))
                || model == null || model.isBlank()) {
            throw new IllegalArgumentException("Reranking requires an HTTP(S) endpoint and model");
        }
        if (enabled && (apiKey == null || apiKey.isBlank())) {
            throw new IllegalArgumentException("Enabled reranking requires rag.post-retrieval.reranking.api-key");
        }
    }

    // Do not expose the credential through generated record diagnostics.
    @Override
    public String toString() {
        return "RerankingProperties[enabled=" + enabled + ", model=" + model + "]";
    }

}
