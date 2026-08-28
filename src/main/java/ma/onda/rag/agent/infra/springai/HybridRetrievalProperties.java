package ma.onda.rag.agent.infra.springai;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties(prefix = "rag.retrieval.balanced")
public record HybridRetrievalProperties(
        @DefaultValue("20") int denseCandidates,
        @DefaultValue("20") int lexicalCandidates,
        @DefaultValue("0.0") double similarityThreshold,
        @DefaultValue("60") int rrfK
) {
    public HybridRetrievalProperties {
        if (denseCandidates < 1 || lexicalCandidates < 1 || rrfK < 1) {
            throw new IllegalArgumentException("BALANCED candidate limits and rrf-k must be positive");
        }
        if (!Double.isFinite(similarityThreshold) || similarityThreshold < 0 || similarityThreshold > 1) {
            throw new IllegalArgumentException("BALANCED similarity-threshold must be between 0 and 1");
        }
    }
}
