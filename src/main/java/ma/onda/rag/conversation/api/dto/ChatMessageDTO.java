package ma.onda.rag.conversation.api.dto;

import ma.onda.rag.conversation.domain.MessageType;

import java.time.LocalDateTime;
import java.util.UUID;

public record ChatMessageDTO(
    UUID id,
    int sequenceNumber,
    MessageType messageType,
    String content,
    String metadata,
    LocalDateTime createdAt
) {}
