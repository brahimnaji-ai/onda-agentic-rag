package ma.onda.rag.identity.application;

import jakarta.ws.rs.core.Response;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import ma.onda.rag.identity.api.dto.request.RegisterRequest;
import ma.onda.rag.identity.api.dto.response.TokenResponse;
import ma.onda.rag.identity.infra.keycloak.KeycloakProperties;
import ma.onda.rag.shared.exception.BusinessException;
import ma.onda.rag.shared.exception.ErrorCode;
import org.keycloak.OAuth2Constants;
import org.keycloak.admin.client.Keycloak;
import org.keycloak.admin.client.KeycloakBuilder;
import org.keycloak.admin.client.resource.RealmResource;
import org.keycloak.admin.client.resource.UsersResource;
import org.keycloak.representations.idm.CredentialRepresentation;
import org.keycloak.representations.idm.RoleRepresentation;
import org.keycloak.representations.idm.UserRepresentation;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Adapter that wraps the Keycloak 24+ Admin REST API and the token endpoint.
 *
 * <p>Responsibilities:
 * <ul>
 *   <li>Create / delete realm users (used by the dual-write registration saga)</li>
 *   <li>Assign the {@code USER} realm role on registration</li>
 *   <li>Obtain, refresh, and revoke tokens via the OIDC token endpoint</li>
 *   <li>Change a user's password via the Admin REST API</li>
 * </ul>
 *
 * <p>All Admin REST calls use a short-lived admin {@link Keycloak} client that
 * authenticates with {@code client_credentials}; it is created fresh per call to
 * avoid stale token issues (Keycloak Admin Client handles token caching internally).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class KeycloakAdminClientService {

    private static final String GRANT_TYPE_PASSWORD = "password";
    private static final String GRANT_TYPE_REFRESH = "refresh_token";
    private static final String REVOKE_PATH = "/protocol/openid-connect/logout";
    private static final String TOKEN_PATH = "/protocol/openid-connect/token";

    private final KeycloakProperties props;
    private final RestTemplate restTemplate;

    // Admin REST operations
    /**
     * Creates a new Keycloak realm user, sets their password credential, and
     * assigns the {@code USER} realm role.
     *
     * @param request the registration payload
     * @return the Keycloak UUID (sub claim) of the newly created user
     * @throws BusinessException if Keycloak rejects the creation
     */


    public String createUserInKeycloak(RegisterRequest request) {
        log.info("Creating Keycloak user for username='{}'", request.username());

        try (Keycloak adminClient = buildAdminClient()) {

            RealmResource realm = adminClient.realm(props.getRealm());
            UsersResource users = realm.users();

            UserRepresentation user = buildUserRepresentation(request);

            try (Response response = users.create(user)) {

                int status = response.getStatus();

                if (status != 201) {
                    String body = response.readEntity(String.class);

                    log.error("Keycloak user creation failed: HTTP {} — {}", status, body);

                    throw new BusinessException(ErrorCode.KEYCLOAK_USER_CREATION_FAILED, status, body);
                }

                // Extract the new user's UUID from the Location header
                String location = response.getHeaderString("Location");
                String keycloakId = location.substring(location.lastIndexOf('/') + 1);

                log.info("Keycloak user created: keycloakId='{}'", keycloakId);

                // Assign USER realm role
                assignRole(realm, keycloakId, "USER");

                return keycloakId;
            }
        }
    }

    /**
     * Deletes a Keycloak user by ID.
     *
     * <p>This is the <em>compensation action</em> of the dual-write saga: if the
     * local PostgreSQL save fails after the Keycloak user was created, this method
     * removes the orphan Keycloak user.
     *
     * @param keycloakId the Keycloak UUID of the user to delete
     */
    public void deleteUserInKeycloak(String keycloakId) {

        log.warn("Compensating: deleting Keycloak user keycloakId='{}'", keycloakId);

        try (Keycloak adminClient = buildAdminClient()) {
            adminClient.realm(props.getRealm()).users().delete(keycloakId);

            log.info("Compensation successful: Keycloak user '{}' deleted", keycloakId);
        } catch (Exception ex) {
            // Log but do not rethrow — compensation failure is recorded for manual remediation
            log.error("Failed to delete orphan Keycloak user '{}': {}", keycloakId, ex.getMessage(), ex);
        }
    }

    /**
     * Changes the password for the given Keycloak user.
     *
     * @param keycloakId  the Keycloak UUID of the target user
     * @param newPassword the new plain-text password (Keycloak will hash it)
     */

    public void changePassword(String keycloakId, String newPassword) {
        log.info("Changing password for Keycloak user '{}'", keycloakId);
        try (Keycloak adminClient = buildAdminClient()) {
            CredentialRepresentation credential = buildPasswordCredential(newPassword);
            adminClient.realm(props.getRealm())
                    .users()
                    .get(keycloakId)
                    .resetPassword(credential);
            log.info("Password changed successfully for keycloakId='{}'", keycloakId);
        }
    }

    // Token endpoint operations

    /**
     * Exchanges username/password credentials for access + refresh tokens.
     *
     * @param username the user's username
     * @param password the user's plain-text password
     * @return a {@link TokenResponse} containing the JWT access/refresh tokens
     */

    public TokenResponse obtainTokens(String username, String password) {

        log.debug("Obtaining tokens for username='{}'", username);

        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", GRANT_TYPE_PASSWORD);
        form.add("client_id", props.getClientId());
        form.add("client_secret", props.getClientSecret());
        form.add("username", username);
        form.add("password", password);

        return postToTokenEndpoint(form);
    }

    /**
     * Exchanges a refresh token for a new pair of access + refresh tokens.
     *
     * @param refreshToken the current refresh token
     * @return a new {@link TokenResponse}
     */

    public TokenResponse refreshToken(String refreshToken) {
        log.debug("Refreshing token");
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", GRANT_TYPE_REFRESH);
        form.add("client_id", props.getClientId());
        form.add("client_secret", props.getClientSecret());
        form.add(GRANT_TYPE_REFRESH, refreshToken);
        return postToTokenEndpoint(form);
    }

    /**
     * Revokes a refresh token at the Keycloak logout endpoint, invalidating
     * the associated Keycloak session.
     *
     * @param refreshToken the refresh token to revoke
     */

    public void logout(String refreshToken) {
        log.info("Revoking refresh token / logging out");
        String url = realmBaseUrl() + REVOKE_PATH;
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("client_id", props.getClientId());
        form.add("client_secret", props.getClientSecret());
        form.add(GRANT_TYPE_REFRESH, refreshToken);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
        HttpEntity<MultiValueMap<String, String>> entity = new HttpEntity<>(form, headers);

        ResponseEntity<Void> response = restTemplate.postForEntity(url, entity, Void.class);
        log.info("Logout response HTTP {}", response.getStatusCode());
    }




    // Helpers

    private TokenResponse postToTokenEndpoint(MultiValueMap<String, String> form) {
        String url = realmBaseUrl() + TOKEN_PATH;
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
        HttpEntity<MultiValueMap<String, String>> entity = new HttpEntity<>(form, headers);

        @SuppressWarnings("unchecked")
        ResponseEntity<Map<String, Object>> response =
                (ResponseEntity<Map<String, Object>>) (ResponseEntity<?>) restTemplate.postForEntity(url, entity, Map.class);

        Map<String, Object> body = response.getBody();
        if (body == null) {
            throw new BusinessException(ErrorCode.KEYCLOAK_TOKEN_RESPONSE_EMPTY);
        }

        return new TokenResponse(
                (String) body.get("access_token"),
                (String) body.get("refresh_token"),
                (String) body.getOrDefault("token_type", "Bearer"),
                toLong(body.get("expires_in")),
                toLong(body.get("refresh_expires_in"))
        );
    }

    private void assignRole(RealmResource realm, String keycloakId, String roleName) {
        try {
            RoleRepresentation role = realm.roles().get(roleName).toRepresentation();
            realm.users().get(keycloakId)
                    .roles()
                    .realmLevel()
                    .add(Collections.singletonList(role));
            log.info("Assigned role '{}' to Keycloak user '{}'", roleName, keycloakId);
        } catch (Exception ex) {
            // Fail fast — a missing realm role is a misconfiguration, not a transient error.
            // The Keycloak user was already created at this point; the caller's compensation
            // logic in RegistrationService will delete the orphan if we throw.
            log.error("Failed to assign role '{}' to Keycloak user '{}': {}", roleName, keycloakId, ex.getMessage());
            throw new BusinessException(ErrorCode.KEYCLOAK_ROLE_NOT_FOUND, roleName);
        }
    }

    private UserRepresentation buildUserRepresentation(RegisterRequest request) {
        UserRepresentation user = new UserRepresentation();
        user.setUsername(request.username());
        user.setEmail(request.email());
        user.setFirstName(request.firstName());
        user.setLastName(request.lastName());
        user.setEnabled(true);
        user.setEmailVerified(false);
        user.setCredentials(List.of(buildPasswordCredential(request.password())));
        return user;
    }

    private CredentialRepresentation buildPasswordCredential(String password) {
        CredentialRepresentation cred = new CredentialRepresentation();
        cred.setType(CredentialRepresentation.PASSWORD);
        cred.setValue(password);
        cred.setTemporary(false);
        return cred;
    }

    private Keycloak buildAdminClient() {
        return KeycloakBuilder.builder()
                .serverUrl(props.getServerUrl())
                .realm(props.getRealm())
                .grantType(OAuth2Constants.CLIENT_CREDENTIALS)
                .clientId(props.getClientId())
                .clientSecret(props.getClientSecret())
                .build();
    }

    private String realmBaseUrl() {
        return "%s/realms/%s".formatted(props.getServerUrl(), props.getRealm());
    }

    private long toLong(Object value) {
        if (value instanceof Number n) return n.longValue();
        return 0L;
    }
}
