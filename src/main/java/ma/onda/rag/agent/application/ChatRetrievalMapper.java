package ma.onda.rag.agent.application;

import ma.onda.rag.agent.application.retrieval.RetrievalProfile;
import ma.onda.rag.agent.application.retrieval.RetrievalResult;
import ma.onda.rag.agent.application.retrieval.RetrievalStage;
import ma.onda.rag.agent.infra.retrieval.HybridDocumentRetriever;
import ma.onda.rag.conversation.api.CitedSourceDTO;
import ma.onda.rag.conversation.api.RetrievalExecutionDTO;
import ma.onda.rag.conversation.api.SourceRetrievalDTO;
import org.springframework.ai.document.Document;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Maps one immutable request snapshot to API evidence, keeping execution/source links consistent. */
final class ChatRetrievalMapper {

    private ChatRetrievalMapper() {}

    static Evidence map(List<RetrievalResult> results) {
        List<CitedSourceDTO> sources = new ArrayList<>();
        List<RetrievalExecutionDTO> executions = new ArrayList<>();
        for (int index = 0; index < results.size(); index++) {
            RetrievalResult result = results.get(index);
            int executionId = index + 1;
            Map<RetrievalStage, Double> timings = new EnumMap<>(RetrievalStage.class);
            result.timings().forEach((stage, duration) -> timings.put(stage, duration.toNanos() / 1_000_000.0));
            executions.add(new RetrievalExecutionDTO(executionId, result.profile(), result.executedQueries(),
                    result.documents().size(), timings));

            Map<String, Document> documents = new HashMap<>();
            result.documents().forEach(document -> documents.put(document.getId(), document));
            for (RetrievalResult.Source source : result.sources()) {
                sources.add(new CitedSourceDTO("DOCUMENT", source.documentId(), source.sourceFilename(), null,
                        source.chunkContent(), source.relevanceScore(),
                        details(executionId, result.profile(), source, documents.get(source.chunkId()))));
            }
        }
        return new Evidence(List.copyOf(sources), List.copyOf(executions));
    }

    private static SourceRetrievalDTO details(int executionId, RetrievalProfile profile,
                                               RetrievalResult.Source source, Document document) {
        if (profile == RetrievalProfile.FAST) {
            // FAST does not retain original dense ranks through joining and post-processing.
            return new SourceRetrievalDTO(executionId, source.chunkId(), profile,
                    SourceRetrievalDTO.ScoreType.COSINE_SIMILARITY, null,
                    new SourceRetrievalDTO.Arm(null, source.relevanceScore()), null);
        }
        Object value = document == null ? null : document.getMetadata().get(HybridDocumentRetriever.DIAGNOSTICS_KEY);
        Map<?, ?> diagnostics = value instanceof Map<?, ?> map ? map : Map.of();
        return new SourceRetrievalDTO(executionId, source.chunkId(), profile, SourceRetrievalDTO.ScoreType.RRF,
                integer(diagnostics.get("rrf_k")), arm(diagnostics, "dense"), arm(diagnostics, "lexical"));
    }

    private static SourceRetrievalDTO.Arm arm(Map<?, ?> diagnostics, String name) {
        Integer rank = integer(diagnostics.get(name + "_rank"));
        if (rank == null) return null;
        Object value = diagnostics.get(name + "_score");
        return new SourceRetrievalDTO.Arm(rank, value instanceof Number number ? number.doubleValue() : null);
    }

    private static Integer integer(Object value) {
        return value instanceof Number number ? number.intValue() : null;
    }

    record Evidence(List<CitedSourceDTO> sources, List<RetrievalExecutionDTO> retrievals) {}
}
