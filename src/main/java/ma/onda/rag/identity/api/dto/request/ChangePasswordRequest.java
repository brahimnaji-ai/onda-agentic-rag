package ma.onda.rag.identity.api.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Payload for the {@code PUT /api/v1/auth/change-password} endpoint.
 */
public record ChangePasswordRequest(

        @NotBlank(message = "New password is required")
        @Size(min = 8, message = "New password must be at least 8 characters")
        String newPassword
) {}
