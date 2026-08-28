package ma.onda.rag.conversation.api;

import ma.onda.rag.agent.application.retrieval.RetrievalProfile;

/** Describes the source's relevanceScore without exposing arbitrary document metadata. */
public record SourceRetrievalDTO(
        int executionId,
        String chunkId,
        RetrievalProfile profile,
        ScoreType scoreType,
        Integer rrfK,
        Arm dense,
        Arm lexical,
        Double rrfScore,
        Double rerankerScore,
        String rerankerStatus,
        Integer contextTokens
) {
    public enum ScoreType { COSINE_SIMILARITY, RRF, ADJACENT_CONTEXT }

    /** Rank is the original arm rank, not the final selected-source position. */
    public record Arm(Integer rank, Double score) {}
}
