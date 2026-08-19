package ma.onda.rag.agent.infra.tools;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import ma.onda.rag.document.infra.VectorMetadataKeys;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Description;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

@Slf4j
@Component("vectorSearchTool")
@EnableConfigurationProperties(VectorSearchProperties.class)
@Description("Search PostgreSQL pgvector vector store for relevant document snippets")
@RequiredArgsConstructor
public class VectorSearchTool implements Function<VectorSearchTool.Request, VectorSearchTool.Response> {

    private final VectorStore vectorStore;
    private final VectorSearchProperties properties;

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

        log.info("Executing vector similarity search for query: '{}' with topK={} and threshold={}",
                request.query(), properties.topK(), properties.similarityThreshold());

        SearchRequest searchRequest = SearchRequest.builder()
                .query(request.query())
                .topK(properties.topK())
                .similarityThreshold(properties.similarityThreshold())
                .build();

        List<Document> documents = vectorStore.similaritySearch(searchRequest);

        if (documents == null || documents.isEmpty()) {
            log.info("No documents found matching query: '{}' above similarity threshold {}",
                    request.query(), properties.similarityThreshold());
            return new Response(Collections.emptyList());
        }

        List<DocumentSnippet> snippets = documents.stream()
                .map(this::mapToSnippet)
                .toList();

        log.info("VectorSearchTool returned {} document snippets", snippets.size());
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
}
