package ma.onda.rag.conversation.api;

public record CitedSourceDTO(
        String type,
        String documentId,
        String title,
        String url,
        String snippet,
        Double relevanceScore,
        SourceRetrievalDTO retrieval
) {
    /** Web evidence has no private-document retrieval diagnostics. */
    public CitedSourceDTO(String type, String documentId, String title, String url,
                          String snippet, Double relevanceScore) {
        this(type, documentId, title, url, snippet, relevanceScore, null);
    }
}
