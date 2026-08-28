package ma.onda.rag.conversation.api;

import ma.onda.rag.agent.application.retrieval.RetrievalProfile;
import ma.onda.rag.agent.application.retrieval.RetrievalStage;

import java.util.List;
import java.util.Map;

/** One completed private-document tool call; id is local to this chat response. */
public record RetrievalExecutionDTO(
        int id,
        RetrievalProfile profile,
        List<String> executedQueries,
        int selectedChunkCount,
        Map<RetrievalStage, Double> timingsMs
) {
    public RetrievalExecutionDTO {
        executedQueries = List.copyOf(executedQueries);
        timingsMs = Map.copyOf(timingsMs);
    }
}
