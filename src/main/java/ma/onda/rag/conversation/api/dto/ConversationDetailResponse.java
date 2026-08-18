package ma.onda.rag.conversation.api.dto;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/** A conversation together with its deterministically ordered history. */

public record ConversationDetailResponse(
    UUID id,
    String title,
    List<ChatMessageDTO> messages,
    LocalDateTime createdAt
) {}
