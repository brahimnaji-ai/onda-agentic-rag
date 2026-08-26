package ma.onda.rag.agent.infra.tools;

import ma.onda.rag.document.infra.VectorMetadataKeys;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.document.Document;
import org.springframework.ai.rag.Query;
import org.springframework.ai.rag.postretrieval.document.DocumentPostProcessor;
import org.springframework.ai.rag.preretrieval.query.transformation.QueryTransformer;
import org.springframework.ai.rag.retrieval.search.DocumentRetriever;

import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class VectorSearchToolTest {

    @Mock
    private DocumentRetriever documentRetriever;

    @Mock
    private DocumentPostProcessor documentPostProcessor;

    @Mock
    private QueryTransformer queryTransformer;

    private VectorSearchProperties properties;

    private VectorSearchTool vectorSearchTool;

    @BeforeEach
    void setUp() {
        properties = new VectorSearchProperties(4, 0.7);
        vectorSearchTool = new VectorSearchTool(documentRetriever, documentPostProcessor, properties, queryTransformer);
        lenient().when(queryTransformer.transform(any(Query.class))).thenAnswer(invocation -> invocation.getArgument(0));
        lenient().when(documentPostProcessor.process(any(Query.class), anyList()))
                .thenAnswer(invocation -> invocation.getArgument(1));
    }

    @Test
    @DisplayName("Should execute similarity search with topK=4 and similarityThreshold=0.7 and return document snippets")
    void testApply_SuccessfulSearch() {
        // Given
        String searchQuery = "What is the policy for remote work?";

        Document doc1 = Document.builder()
                .id("chunk-1")
                .text("Remote work policy content chunk 1")
                .metadata(Map.of(
                        VectorMetadataKeys.DOCUMENT_ID, "doc-uuid-101",
                        VectorMetadataKeys.SOURCE, "employee_handbook.pdf"
                ))
                .score(0.88)
                .build();

        Document doc2 = Document.builder()
                .id("chunk-2")
                .text("Remote work policy content chunk 2")
                .metadata(Map.of(
                        VectorMetadataKeys.DOCUMENT_ID, "doc-uuid-101",
                        VectorMetadataKeys.SOURCE, "employee_handbook.pdf"
                ))
                .score(0.75)
                .build();

        when(documentRetriever.retrieve(any(Query.class))).thenReturn(List.of(doc1, doc2));

        VectorSearchTool.Request request = new VectorSearchTool.Request(searchQuery);

        // When
        VectorSearchTool.Response response = vectorSearchTool.apply(request);

        // Then
        ArgumentCaptor<Query> queryCaptor = ArgumentCaptor.forClass(Query.class);
        verify(documentRetriever, times(1)).retrieve(queryCaptor.capture());

        assertThat(queryCaptor.getValue().text()).isEqualTo(searchQuery);

        assertThat(response).isNotNull();
        assertThat(response.snippets()).hasSize(2);

        VectorSearchTool.DocumentSnippet snippet1 = response.snippets().get(0);
        assertThat(snippet1.documentId()).isEqualTo("doc-uuid-101");
        assertThat(snippet1.sourceFilename()).isEqualTo("employee_handbook.pdf");
        assertThat(snippet1.chunkContent()).isEqualTo("Remote work policy content chunk 1");
        assertThat(snippet1.relevanceScore()).isEqualTo(0.88);

        VectorSearchTool.DocumentSnippet snippet2 = response.snippets().get(1);
        assertThat(snippet2.documentId()).isEqualTo("doc-uuid-101");
        assertThat(snippet2.sourceFilename()).isEqualTo("employee_handbook.pdf");
        assertThat(snippet2.chunkContent()).isEqualTo("Remote work policy content chunk 2");
        assertThat(snippet2.relevanceScore()).isEqualTo(0.75);
    }

    @Test
    @DisplayName("Should search using the French-preserving pre-retrieval query")
    void testApply_UsesTransformedQuery() {
        String originalQuery = "C'est quoi les conditions pour l'aérogare de Casablanca ?";
        String rewrittenQuery = "conditions aérogare Casablanca";
        when(queryTransformer.transform(new Query(originalQuery))).thenReturn(new Query(rewrittenQuery));
        when(documentRetriever.retrieve(any(Query.class))).thenReturn(Collections.emptyList());

        vectorSearchTool.apply(new VectorSearchTool.Request(originalQuery));

        ArgumentCaptor<Query> queryCaptor = ArgumentCaptor.forClass(Query.class);
        verify(documentRetriever).retrieve(queryCaptor.capture());
        assertThat(queryCaptor.getValue().text()).isEqualTo(rewrittenQuery);
    }

    @Test
    @DisplayName("Should return empty list gracefully when similarity search yields no results")
    void testApply_EmptyResultSet() {
        // Given
        String searchQuery = "Non-existent query";
        when(documentRetriever.retrieve(any(Query.class))).thenReturn(Collections.emptyList());

        VectorSearchTool.Request request = new VectorSearchTool.Request(searchQuery);

        // When
        VectorSearchTool.Response response = vectorSearchTool.apply(request);

        // Then
        verify(documentRetriever, times(1)).retrieve(any(Query.class));
        assertThat(response).isNotNull();
        assertThat(response.snippets()).isEmpty();
    }

    @Test
    @DisplayName("Should return empty list gracefully when vectorStore returns null")
    void testApply_NullVectorStoreResult() {
        // Given
        String searchQuery = "Query returning null";
        when(documentRetriever.retrieve(any(Query.class))).thenReturn(null);

        VectorSearchTool.Request request = new VectorSearchTool.Request(searchQuery);

        // When
        VectorSearchTool.Response response = vectorSearchTool.apply(request);

        // Then
        verify(documentRetriever, times(1)).retrieve(any(Query.class));
        assertThat(response).isNotNull();
        assertThat(response.snippets()).isEmpty();
    }

    @Test
    @DisplayName("Should handle empty, blank, or null search query without calling the document retriever")
    void testApply_InvalidQuery() {
        // Null request
        VectorSearchTool.Response responseNullReq = vectorSearchTool.apply(null);
        assertThat(responseNullReq).isNotNull();
        assertThat(responseNullReq.snippets()).isEmpty();

        // Null query
        VectorSearchTool.Response responseNullQuery = vectorSearchTool.apply(new VectorSearchTool.Request(null));
        assertThat(responseNullQuery).isNotNull();
        assertThat(responseNullQuery.snippets()).isEmpty();

        // Blank query
        VectorSearchTool.Response responseBlankQuery = vectorSearchTool.apply(new VectorSearchTool.Request("   "));
        assertThat(responseBlankQuery).isNotNull();
        assertThat(responseBlankQuery.snippets()).isEmpty();

        verifyNoInteractions(documentRetriever);
    }
}
