package ma.onda.rag.agent.application.retrieval;

import ma.onda.rag.document.infra.VectorMetadataKeys;
import org.springframework.ai.document.Document;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/** Explicit retrieval evidence and diagnostics; query text must not be logged or tagged. */
public record RetrievalResult(
        List<Document> documents,
        List<Source> sources,
        List<String> executedQueries,
        RetrievalProfile profile,
        Map<RetrievalStage, Duration> timings,
        List<String> candidateChunkIds,
        MeasuredQueryExpander.Diagnostics expansion
) {
    public RetrievalResult {
        documents = List.copyOf(documents);
        sources = List.copyOf(sources);
        executedQueries = List.copyOf(executedQueries);
        timings = Map.copyOf(timings);
        candidateChunkIds = List.copyOf(candidateChunkIds);
    }

    public RetrievalResult(List<Document> documents, List<Source> sources, List<String> executedQueries,
                           RetrievalProfile profile, Map<RetrievalStage, Duration> timings) {
        this(documents, sources, executedQueries, profile, timings,
                documents.stream().map(Document::getId).toList(), MeasuredQueryExpander.Diagnostics.none());
    }

    public static RetrievalResult fromDocuments(List<Document> documents, List<String> executedQueries,
                                               RetrievalProfile profile, Map<RetrievalStage, Duration> timings) {
        List<Source> sources = documents.stream().map(document -> new Source(
                document.getId(),
                metadataValue(document, VectorMetadataKeys.DOCUMENT_ID),
                metadataValue(document, VectorMetadataKeys.SOURCE),
                document.getText(), document.getScore())).toList();
        return new RetrievalResult(documents, sources, executedQueries, profile, timings);
    }

    private static String metadataValue(Document document, String key) {
        Object value = document.getMetadata().get(key);
        return value == null ? null : String.valueOf(value);
    }

    public record Source(String chunkId, String documentId, String sourceFilename,
                         String chunkContent, Double relevanceScore) {}
}
