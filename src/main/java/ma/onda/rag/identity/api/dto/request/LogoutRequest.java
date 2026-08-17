package ma.onda.rag.identity.api.dto.request;

import jakarta.validation.constraints.NotBlank;

/**
 * Payload for the {@code POST /api/v1/auth/logout} endpoint.
 */
public record LogoutRequest(

        @NotBlank(message = "Refresh token is required to invalidate the session")
        String refreshToken
) {}
