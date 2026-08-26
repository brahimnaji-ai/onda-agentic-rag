package ma.onda.rag.agent.infra.springai;

import ma.onda.rag.agent.infra.tools.VectorSearchProperties;
import org.springframework.ai.rag.retrieval.search.DocumentRetriever;
import org.springframework.ai.rag.retrieval.search.VectorStoreDocumentRetriever;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(VectorSearchProperties.class)
class RagRetrievalConfig {

    @Bean("ondaDocumentRetriever")
    DocumentRetriever ondaDocumentRetriever(VectorStore vectorStore, VectorSearchProperties properties) {
        return VectorStoreDocumentRetriever.builder()
                .vectorStore(vectorStore)
                .topK(properties.topK())
                .similarityThreshold(properties.similarityThreshold())
                .build();
    }
}
