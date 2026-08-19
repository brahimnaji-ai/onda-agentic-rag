package ma.onda.rag.conversation.api;

public record TokenUsageDTO(
        Long promptTokens,
        Long completionTokens,
        Long totalTokens
) {}
