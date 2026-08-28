package ma.onda.rag.agent.application.retrieval;

import java.util.List;
import java.util.Map;

/** Bulk query/chunk scoring port. Providers never receive or replace citation metadata. */
@FunctionalInterface
public interface DocumentReranker {
    Result rerank(String query, List<Candidate> candidates);

    record Candidate(String chunkId, String text) {}

    enum Status { SUCCESS, DISABLED, TIMEOUT, ERROR, CIRCUIT_OPEN, BULKHEAD_FULL, INTERRUPTED }

    record Result(Map<String, Double> scores, Status status) {
        public Result {
            scores = Map.copyOf(scores);
            java.util.Objects.requireNonNull(status, "status");
        }

        public static Result unavailable(Status status) {
            return new Result(Map.of(), status);
        }
    }
}
