package ma.onda.rag.conversation.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;

public record ChatRequest(
        @NotNull UUID conversationId,
        @NotBlank String message
) {}
