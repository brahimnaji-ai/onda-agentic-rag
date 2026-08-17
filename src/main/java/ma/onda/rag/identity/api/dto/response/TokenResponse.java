package ma.onda.rag.identity.api.dto.response;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Token payload returned on successful login or token refresh.
 *
 * <p>Field names are snake_case to match the Keycloak token endpoint response
 * schema, making transparent proxying straightforward.
 */
public record TokenResponse(

        @JsonProperty("access_token")
        String accessToken,

        @JsonProperty("refresh_token")
        String refreshToken,

        @JsonProperty("token_type")
        String tokenType,

        @JsonProperty("expires_in")
        long expiresIn,

        @JsonProperty("refresh_expires_in")
        long refreshExpiresIn
) {}
