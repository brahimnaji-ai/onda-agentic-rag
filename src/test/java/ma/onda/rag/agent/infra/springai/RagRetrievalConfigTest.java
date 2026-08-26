package ma.onda.rag.agent.infra.springai;

import ma.onda.rag.agent.infra.tools.VectorSearchProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.rag.Query;
import org.springframework.ai.rag.retrieval.search.DocumentRetriever;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RagRetrievalConfigTest {

    @Mock
    private VectorStore vectorStore;

    @Test
    void configuresSpringAiRetrieverWithApplicationRetrievalProperties() {
        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of());

        DocumentRetriever retriever = new RagRetrievalConfig()
                .ondaDocumentRetriever(vectorStore, new VectorSearchProperties(8, 0.65));

        retriever.retrieve(new Query("conditions aérogare Casablanca"));

        ArgumentCaptor<SearchRequest> requestCaptor = ArgumentCaptor.forClass(SearchRequest.class);
        verify(vectorStore).similaritySearch(requestCaptor.capture());
        assertThat(requestCaptor.getValue().getQuery()).isEqualTo("conditions aérogare Casablanca");
        assertThat(requestCaptor.getValue().getTopK()).isEqualTo(8);
        assertThat(requestCaptor.getValue().getSimilarityThreshold()).isEqualTo(0.65);
    }
}
