package ma.onda.rag.agent.infra.springai;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.boot.context.properties.bind.ConstructorBinding;

@ConfigurationProperties(prefix = "rag.post-retrieval")
public record PostRetrievalProperties(@DefaultValue("4") int maxDocuments,
                                     @DefaultValue("4096") int contextTokenBudget,
                                     @DefaultValue("false") boolean adjacentChunksEnabled) {

    public PostRetrievalProperties(int maxDocuments) {
        this(maxDocuments, 4096, false);
    }

    @ConstructorBinding
    public PostRetrievalProperties {
        if (maxDocuments < 1) {
            throw new IllegalArgumentException("rag.post-retrieval.max-documents must be at least 1");
        }
        if (contextTokenBudget < 1) {
            throw new IllegalArgumentException("rag.post-retrieval.context-token-budget must be at least 1");
        }
    }
}
