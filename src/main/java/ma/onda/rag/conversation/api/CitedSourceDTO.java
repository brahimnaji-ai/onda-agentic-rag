package ma.onda.rag.conversation.api;

public record CitedSourceDTO(
        String type,
        String documentId,
        String title,
        String url,
        String snippet,
        Double relevanceScore
) {}
