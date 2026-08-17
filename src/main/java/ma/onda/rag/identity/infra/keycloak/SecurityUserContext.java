package ma.onda.rag.identity.infra.keycloak;

import ma.onda.rag.shared.exception.BusinessException;
import ma.onda.rag.shared.exception.ErrorCode;
import ma.onda.rag.user.domain.User;
import ma.onda.rag.user.infra.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
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
     * @throws BusinessException if no authenticated JWT token is in context or user is not provisioned locally
     */

    public User getCurrentUser() {
        String keycloakId = getCurrentKeycloakId();
        return userRepository.findByKeycloakId(keycloakId)
                .orElseThrow(() -> new BusinessException(ErrorCode.USER_NOT_REGISTERED, keycloakId));
    }

    public String getCurrentKeycloakId() {
        Jwt jwt = getCurrentJwt();
        return jwt.getSubject();
    }

    public Jwt getCurrentJwt() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null) {
            throw new BusinessException(ErrorCode.JWT_NOT_FOUND);
        }
        if (authentication instanceof JwtAuthenticationToken jwtAuthenticationToken) {
            return jwtAuthenticationToken.getToken();
        }
        if (authentication.getPrincipal() instanceof Jwt jwt) {
            return jwt;
        }
        if (authentication.getCredentials() instanceof Jwt jwt) {
            return jwt;
        }
        throw new BusinessException(ErrorCode.JWT_NOT_FOUND);
    }
}
