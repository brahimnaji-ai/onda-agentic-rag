package ma.onda.rag.agent.infra.tools;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties(prefix = "rag.retrieval")
public record VectorSearchProperties(
        @DefaultValue("4") int topK,
        @DefaultValue("0.7") double similarityThreshold
) {}
