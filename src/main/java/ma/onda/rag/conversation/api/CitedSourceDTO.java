package ma.onda.rag.conversation.api;

public record CitedSourceDTO(
        String documentId,
        String title,
        String snippet,
        Double relevanceScore
) {}
