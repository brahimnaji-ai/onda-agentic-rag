package ma.onda.rag.identity.infra.keycloak;

import ma.onda.rag.shared.exception.BusinessException;
import ma.onda.rag.shared.exception.ErrorCode;
import ma.onda.rag.user.domain.User;
import ma.onda.rag.user.infra.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.time.Instant;
import java.util.Collections;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SecurityUserContextTest {

    @Mock
    private UserRepository userRepository;

    @InjectMocks
    private SecurityUserContext securityUserContext;

    @BeforeEach
    void setUp() {
        SecurityContextHolder.clearContext();
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("Should return current Jwt token from SecurityContextHolder")
    void shouldReturnCurrentJwt() {
        Jwt jwt = createJwt("kc-sub-123", "b_naji");
        setJwtInContext(jwt);

        Jwt result = securityUserContext.getCurrentJwt();

        assertThat(result).isNotNull();
        assertThat(result.getSubject()).isEqualTo("kc-sub-123");
    }

    @Test
    @DisplayName("Should return current Keycloak Id (sub claim)")
    void shouldReturnCurrentKeycloakId() {
        Jwt jwt = createJwt("kc-sub-123", "b_naji");
        setJwtInContext(jwt);

        String keycloakId = securityUserContext.getCurrentKeycloakId();

        assertThat(keycloakId).isEqualTo("kc-sub-123");
    }

    @Test
    @DisplayName("Should return domain User entity matching keycloakId")
    void shouldReturnCurrentUser() {
        Jwt jwt = createJwt("kc-sub-123", "b_naji");
        setJwtInContext(jwt);

        User mockUser = User.builder()
                .keycloakId("kc-sub-123")
                .username("b_naji")
                .email("b.naji@onda.ma")
                .firstName("Brahim")
                .lastName("Naji")
                .build();

        when(userRepository.findByKeycloakId("kc-sub-123")).thenReturn(Optional.of(mockUser));

        User result = securityUserContext.getCurrentUser();

        assertThat(result).isNotNull();
        assertThat(result.getKeycloakId()).isEqualTo("kc-sub-123");
        assertThat(result.getEmail()).isEqualTo("b.naji@onda.ma");
    }

    @Test
    @DisplayName("Should throw BusinessException(USER_NOT_REGISTERED) when local user is not registered")
    void shouldThrowBusinessExceptionWhenUserNotFound() {
        Jwt jwt = createJwt("kc-sub-999", "unknown_user");
        setJwtInContext(jwt);

        when(userRepository.findByKeycloakId("kc-sub-999")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> securityUserContext.getCurrentUser())
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> {
                    BusinessException be = (BusinessException) ex;
                    assertThat(be.getErrorCode()).isEqualTo(ErrorCode.USER_NOT_REGISTERED);
                    assertThat(be.getMessage()).contains("not registered");
                });
    }

    @Test
    @DisplayName("Should throw BusinessException(JWT_NOT_FOUND) when no security context authentication exists")
    void shouldThrowBusinessExceptionWhenNoAuthentication() {
        assertThatThrownBy(() -> securityUserContext.getCurrentJwt())
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> {
                    BusinessException be = (BusinessException) ex;
                    assertThat(be.getErrorCode()).isEqualTo(ErrorCode.JWT_NOT_FOUND);
                    assertThat(be.getMessage()).contains("No authenticated JWT token found in security context");
                });
    }

    private Jwt createJwt(String subject, String preferredUsername) {
        return Jwt.withTokenValue("mock-jwt-token")
                .header("alg", "RS256")
                .subject(subject)
                .claim("preferred_username", preferredUsername)
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(3600))
                .build();
    }

    private void setJwtInContext(Jwt jwt) {
        JwtAuthenticationToken authentication = new JwtAuthenticationToken(jwt, Collections.emptyList(), jwt.getClaimAsString("preferred_username"));
        SecurityContextHolder.getContext().setAuthentication(authentication);
    }
}
