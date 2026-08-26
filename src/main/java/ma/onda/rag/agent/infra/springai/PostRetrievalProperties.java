package ma.onda.rag.agent.infra.springai;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties(prefix = "rag.post-retrieval")
public record PostRetrievalProperties(@DefaultValue("4") int maxDocuments) {

    public PostRetrievalProperties {
        if (maxDocuments < 1) {
            throw new IllegalArgumentException("rag.post-retrieval.max-documents must be at least 1");
        }
    }
}
