package ma.onda.rag.identity.infra.keycloak;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class KeycloakJwtAuthenticationConverterTest {

    private KeycloakJwtAuthenticationConverter converter;

    @BeforeEach
    void setUp() {
        converter = new KeycloakJwtAuthenticationConverter();
    }

    @Test
    @DisplayName("Should extract realm roles and prefix them with ROLE_")
    void shouldExtractRealmRolesAndPrefixWithRole() {
        Jwt jwt = createJwt(
                Map.of("realm_access", Map.of("roles", List.of("USER", "ADMIN", "ROLE_INGESTOR"))),
                "b_naji",
                "sub-123"
        );

        AbstractAuthenticationToken authentication = converter.convert(jwt);

        assertThat(authentication).isNotNull();
        assertThat(authentication.getName()).isEqualTo("b_naji");
        assertThat(authentication.getAuthorities())
                .extracting(GrantedAuthority::getAuthority)
                .containsExactlyInAnyOrder("ROLE_USER", "ROLE_ADMIN", "ROLE_INGESTOR");
    }

    @Test
    @DisplayName("Should fallback to subject claim when preferred_username is missing")
    void shouldFallbackToSubjectWhenPreferredUsernameIsMissing() {
        Jwt jwt = createJwt(
                Map.of("realm_access", Map.of("roles", List.of("USER"))),
                null,
                "sub-456"
        );

        AbstractAuthenticationToken authentication = converter.convert(jwt);

        assertThat(authentication).isNotNull();
        assertThat(authentication.getName()).isEqualTo("sub-456");
    }

    @Test
    @DisplayName("Should handle missing realm_access claim gracefully")
    void shouldHandleMissingRealmAccessClaim() {
        Jwt jwt = createJwt(
                Map.of(),
                "b_naji",
                "sub-789"
        );

        AbstractAuthenticationToken authentication = converter.convert(jwt);

        assertThat(authentication).isNotNull();
        assertThat(authentication.getName()).isEqualTo("b_naji");
        assertThat(authentication.getAuthorities()).isEmpty();
    }

    private Jwt createJwt(Map<String, Object> additionalClaims, String preferredUsername, String subject) {
        Jwt.Builder builder = Jwt.withTokenValue("mock-token-value")
                .header("alg", "RS256")
                .subject(subject)
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(3600));

        if (preferredUsername != null) {
            builder.claim("preferred_username", preferredUsername);
        }

        additionalClaims.forEach(builder::claim);

        return builder.build();
    }
}
