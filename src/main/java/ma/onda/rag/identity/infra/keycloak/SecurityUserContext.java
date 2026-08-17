package ma.onda.rag.identity.infra.keycloak;

import ma.onda.rag.shared.exception.AccessDeniedException;
import ma.onda.rag.user.domain.User;
import ma.onda.rag.user.infra.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class SecurityUserContext {

    private final UserRepository userRepository;

    /**
     * Resolves and returns the authenticated local {@link User} entity matching
     * the current Keycloak {@code sub} claim.
     *
     * @return the local User entity
     * @throws AccessDeniedException if no authenticated JWT token is in context or user is not provisioned locally
     */

    public User getCurrentUser() {
        String keycloakId = getCurrentKeycloakId();
        return userRepository.findByKeycloakId(keycloakId)
                .orElseThrow(() -> new AccessDeniedException("User not registered in local database"));
    }

    public String getCurrentKeycloakId() {
        Jwt jwt = getCurrentJwt();
        return jwt.getSubject();
    }

    public Jwt getCurrentJwt() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null) {
            throw new AccessDeniedException("No authenticated JWT token found in security context");
        }
        if (authentication instanceof org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken jwtAuthenticationToken) {
            return jwtAuthenticationToken.getToken();
        }
        if (authentication.getPrincipal() instanceof Jwt jwt) {
            return jwt;
        }
        if (authentication.getCredentials() instanceof Jwt jwt) {
            return jwt;
        }
        throw new AccessDeniedException("No authenticated JWT token found in security context");
    }
}
