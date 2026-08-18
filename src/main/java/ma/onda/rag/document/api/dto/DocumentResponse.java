package ma.onda.rag.document.api.dto;

import ma.onda.rag.document.domain.DocumentEntity;
import ma.onda.rag.document.domain.DocumentStatus;

import java.time.LocalDateTime;
import java.util.UUID;

public record DocumentResponse(
        UUID id,
        String name,
        String contentType,
        long sizeBytes,
        DocumentStatus status,
        UUID uploadedBy,
        LocalDateTime createdAt,
        LocalDateTime lastModifiedAt
) {
    public static DocumentResponse fromEntity(DocumentEntity documentEntity) {
        return new DocumentResponse(
                documentEntity.getId(),
                documentEntity.getName(),
                documentEntity.getContentType(),
                documentEntity.getSizeBytes(),
                documentEntity.getStatus(),
                documentEntity.getUploadedBy() != null ? documentEntity.getUploadedBy().getId() : null,
                documentEntity.getCreatedAt(),
                documentEntity.getLastModifiedAt()
        );
    }
}
