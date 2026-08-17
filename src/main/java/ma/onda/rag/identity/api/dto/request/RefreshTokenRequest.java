package ma.onda.rag.identity.api.dto.request;

import jakarta.validation.constraints.NotBlank;

/**
 * Payload for the {@code POST /api/v1/auth/refresh} endpoint.
 */
public record RefreshTokenRequest(

        @NotBlank(message = "Refresh token is required")
        String refreshToken
) {}
