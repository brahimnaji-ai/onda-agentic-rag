package ma.onda.rag.agent.infra.tools;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import lombok.extern.slf4j.Slf4j;
import ma.onda.rag.document.infra.VectorMetadataKeys;
import org.springframework.ai.document.Document;
import org.springframework.ai.rag.Query;
import org.springframework.ai.rag.preretrieval.query.transformation.QueryTransformer;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Description;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

@Slf4j
@Component("vectorSearchTool")
@EnableConfigurationProperties(VectorSearchProperties.class)
@Description("Search PostgreSQL pgvector vector store for relevant document snippets")
public class VectorSearchTool implements Function<VectorSearchTool.Request, VectorSearchTool.Response> {

    private final VectorStore vectorStore;
    private final VectorSearchProperties properties;
    private final QueryTransformer queryTransformer;

    private static final ThreadLocal<List<DocumentSnippet>> lastSnippets = new ThreadLocal<>();

    public VectorSearchTool(VectorStore vectorStore, VectorSearchProperties properties,
                            @Qualifier("frenchQueryTransformer") ObjectProvider<QueryTransformer> queryTransformerProvider) {
        this(vectorStore, properties, queryTransformerProvider.getIfAvailable(() -> query -> query));
    }

    VectorSearchTool(VectorStore vectorStore, VectorSearchProperties properties, QueryTransformer queryTransformer) {
        this.vectorStore = vectorStore;
        this.properties = properties;
        this.queryTransformer = queryTransformer;
    }

    public static List<DocumentSnippet> getLastSnippets() {
        return lastSnippets.get();
    }

    public static void clearLastSnippets() {
        lastSnippets.remove();
    }

    public record Request(
            @JsonPropertyDescription("The natural language search query")
            String query
    ) {}

    public record Response(
            List<DocumentSnippet> snippets
    ) {}

    public record DocumentSnippet(
            String documentId,
            String sourceFilename,
            String chunkContent,
            Double relevanceScore
    ) {}

    @Override
    public Response apply(Request request) {
        if (request == null || request.query() == null || request.query().isBlank()) {
            log.warn("VectorSearchTool called with empty or null query");
            return new Response(Collections.emptyList());
        }

        String searchQuery = transformQuery(request.query());
        log.info("Executing vector similarity search with topK={} and threshold={}",
                properties.topK(), properties.similarityThreshold());

        SearchRequest searchRequest = SearchRequest.builder()
                .query(searchQuery)
                .topK(properties.topK())
                .similarityThreshold(properties.similarityThreshold())
                .build();

        List<Document> documents = vectorStore.similaritySearch(searchRequest);

        if (documents == null || documents.isEmpty()) {
            log.info("No documents found above similarity threshold {}", properties.similarityThreshold());
            return new Response(Collections.emptyList());
        }

        List<DocumentSnippet> snippets = documents.stream()
                .map(this::mapToSnippet)
                .toList();

        log.info("VectorSearchTool returned {} document snippets", snippets.size());
        
        List<DocumentSnippet> currentSnippets = lastSnippets.get();
        if (currentSnippets == null) {
            currentSnippets = new ArrayList<>();
        }
        currentSnippets.addAll(snippets);
        lastSnippets.set(currentSnippets);

        return new Response(snippets);
    }

    private DocumentSnippet mapToSnippet(Document doc) {
        Map<String, Object> metadata = doc.getMetadata();
        String documentId = metadata != null && metadata.containsKey(VectorMetadataKeys.DOCUMENT_ID)
                ? String.valueOf(metadata.get(VectorMetadataKeys.DOCUMENT_ID))
                : null;
        String sourceFilename = metadata != null && metadata.containsKey(VectorMetadataKeys.SOURCE)
                ? String.valueOf(metadata.get(VectorMetadataKeys.SOURCE))
                : null;
        String content = doc.getText();
        Double score = doc.getScore();

        return new DocumentSnippet(documentId, sourceFilename, content, score);
    }

    private String transformQuery(String originalQuery) {
        Query transformedQuery = queryTransformer.transform(new Query(originalQuery));
        if (transformedQuery == null || transformedQuery.text() == null || transformedQuery.text().isBlank()) {
            log.warn("Pre-retrieval query transformation returned an empty query; using the original query");
            return originalQuery;
        }
        return transformedQuery.text();
    }
}
