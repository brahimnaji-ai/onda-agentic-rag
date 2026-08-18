package ma.onda.rag.conversation.api.dto;

import java.time.LocalDateTime;
import java.util.UUID;

/** Summary representation used in the conversation list and create response. */

public record ConversationResponse(
    UUID id,
    String title,
    LocalDateTime createdAt,
    LocalDateTime lastModifiedAt
) {}
