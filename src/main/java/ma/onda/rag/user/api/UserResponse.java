package ma.onda.rag.user.api;

import java.time.LocalDateTime;
import java.util.UUID;

public record UserResponse(
    UUID uuid,
    String keycloakId,
    String username,
    String email,
    String firstName,
    String lastName,
    LocalDateTime createdAt
) {
}
