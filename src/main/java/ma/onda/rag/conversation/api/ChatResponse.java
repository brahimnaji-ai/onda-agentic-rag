package ma.onda.rag.conversation.api;

import java.util.List;

public record ChatResponse(
        String answer,
        List<CitedSourceDTO> sources,
        TokenUsageDTO tokenUsage
) {}
