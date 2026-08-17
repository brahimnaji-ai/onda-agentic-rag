package ma.onda.rag.identity.infra.keycloak;

import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Custom JWT Converter for Keycloak realm roles.
 *
 * <p>Keycloak places realm roles inside the {@code realm_access.roles} JSON claim.
 * This converter extracts those roles, prefixes them with {@code ROLE_} to conform to
 * Spring Security's authority conventions (e.g. {@code ROLE_USER}, {@code ROLE_ADMIN}),
 * and sets the principal name to Keycloak's {@code preferred_username} claim (or {@code sub} fallback).
 */

@Component
public class KeycloakJwtAuthenticationConverter implements Converter<Jwt, AbstractAuthenticationToken> {

    private static final String REALM_ACCESS_CLAIM = "realm_access";
    private static final String ROLES_CLAIM = "roles";
    private static final String PREFERRED_USERNAME_CLAIM = "preferred_username";

    @Override
    public AbstractAuthenticationToken convert(Jwt jwt) {
        Collection<GrantedAuthority> authorities = extractRealmRoles(jwt);
        String principalName = extractPrincipalName(jwt);
        return new JwtAuthenticationToken(jwt, authorities, principalName);
    }

    private String extractPrincipalName(Jwt jwt) {
        String preferredUsername = jwt.getClaimAsString(PREFERRED_USERNAME_CLAIM);
        if (preferredUsername!=null && !preferredUsername.isBlank()){
            return preferredUsername;
        }
        return jwt.getSubject();
    }

    @SuppressWarnings("unchecked")
    private Collection<GrantedAuthority> extractRealmRoles(Jwt jwt) {
        Map<String, Object> realmAccess = jwt.getClaim(REALM_ACCESS_CLAIM);
        if (realmAccess == null || !realmAccess.containsKey(ROLES_CLAIM)) {
            return Collections.emptyList();
        }

        Object rolesObject =  realmAccess.get(ROLES_CLAIM);
        if (!(rolesObject instanceof List<?> rolesList)) {
            return Collections.emptyList();
        }
        return rolesList.stream()
                .filter(String.class::isInstance)
                .map(String.class::cast)
                .map(role -> role.startsWith("ROLE_") ? role : "ROLE_" + role)
                .map(SimpleGrantedAuthority::new)
                .collect(Collectors.toList());
    }
}
