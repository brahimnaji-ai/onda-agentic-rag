package ma.onda.rag.identity.application;

import jakarta.ws.rs.core.Response;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import ma.onda.rag.identity.api.dto.request.RegisterRequest;
import ma.onda.rag.identity.api.dto.response.TokenResponse;
import ma.onda.rag.identity.infra.keycloak.KeycloakProperties;
import ma.onda.rag.shared.exception.BusinessException;
import ma.onda.rag.shared.exception.ErrorCode;
import org.keycloak.admin.client.Keycloak;
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

import java.util.List;
import java.util.Map;

/**
 * Performs Keycloak user administration and OIDC token operations.
 *
 * <p>The injected Keycloak client authenticates as the configured service account.
 * Its token is refreshed by the Keycloak Admin Client when needed.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class KeycloakAdminClientService {

    private static final String GRANT_TYPE_PASSWORD = "password";
    private static final String GRANT_TYPE_REFRESH = "refresh_token";
    private static final String REVOKE_PATH = "/protocol/openid-connect/logout";
    private static final String TOKEN_PATH = "/protocol/openid-connect/token";
    private static final String DEFAULT_USER_ROLE = "USER";

    private final KeycloakProperties props;
    private final RestTemplate restTemplate;
    private final Keycloak keycloak;

    public String createUserInKeycloak(RegisterRequest request) {
        log.info("Creating Keycloak user for username='{}'", request.username());

        RealmResource realm = targetRealm();
        try (Response response = createUser(realm, request)) {
            ensureCreated(response);

            String keycloakId = extractCreatedUserId(response);
            assignRole(realm, keycloakId, DEFAULT_USER_ROLE);
            log.info("Keycloak user created: keycloakId='{}'", keycloakId);
            return keycloakId;
        } catch (BusinessException exception) {
            throw exception;
        } catch (Exception exception) {
            throw userCreationUnavailable(exception);
        }
    }

    public void deleteUserInKeycloak(String keycloakId) {
        log.warn("Compensating: deleting Keycloak user keycloakId='{}'", keycloakId);

        try {
            targetRealm().users().delete(keycloakId);
            log.info("Compensation successful: Keycloak user '{}' deleted", keycloakId);
        } catch (Exception exception) {
            log.error("Failed to delete orphan Keycloak user '{}': {}", keycloakId, exception.getMessage(), exception);
        }
    }

    public void changePassword(String keycloakId, String newPassword) {
        log.info("Changing password for Keycloak user '{}'", keycloakId);
        targetRealm().users().get(keycloakId).resetPassword(buildPasswordCredential(newPassword));
        log.info("Password changed successfully for keycloakId='{}'", keycloakId);
    }

    public TokenResponse obtainTokens(String username, String password) {
        log.debug("Obtaining tokens for username='{}'", username);
        MultiValueMap<String, String> form = clientCredentialsForm(GRANT_TYPE_PASSWORD);
        form.add("username", username);
        form.add("password", password);
        return postToTokenEndpoint(form);
    }

    public TokenResponse refreshToken(String refreshToken) {
        log.debug("Refreshing token");
        MultiValueMap<String, String> form = clientCredentialsForm(GRANT_TYPE_REFRESH);
        form.add("refresh_token", refreshToken);
        return postToTokenEndpoint(form);
    }

    public void logout(String refreshToken) {
        log.info("Revoking refresh token / logging out");
        MultiValueMap<String, String> form = clientCredentialsForm(GRANT_TYPE_REFRESH);
        form.add("refresh_token", refreshToken);

        ResponseEntity<Void> response = restTemplate.postForEntity(
                realmBaseUrl() + REVOKE_PATH,
                formRequest(form),
                Void.class
        );
        log.info("Logout response HTTP {}", response.getStatusCode());
    }

    private RealmResource targetRealm() {
        return keycloak.realm(props.realm());
    }

    private Response createUser(RealmResource realm, RegisterRequest request) {
        UsersResource users = realm.users();
        return users.create(buildUserRepresentation(request));
    }

    private void ensureCreated(Response response) {
        int status = response.getStatus();
        if (status == Response.Status.CREATED.getStatusCode()) {
            return;
        }

        String body = response.hasEntity() ? response.readEntity(String.class) : "empty response";
        log.error("Keycloak user creation failed: HTTP {} - {}", status, body);
        throw new BusinessException(ErrorCode.KEYCLOAK_USER_CREATION_FAILED, status, body);
    }

    private String extractCreatedUserId(Response response) {
        String location = response.getHeaderString(HttpHeaders.LOCATION);
        if (location == null || location.isBlank() || location.endsWith("/")) {
            throw new BusinessException(
                    ErrorCode.KEYCLOAK_USER_CREATION_FAILED,
                    Response.Status.BAD_GATEWAY.getStatusCode(),
                    "Keycloak did not return a user Location header"
            );
        }
        return location.substring(location.lastIndexOf('/') + 1);
    }

    private void assignRole(RealmResource realm, String keycloakId, String roleName) {
        try {
            RoleRepresentation role = realm.roles().get(roleName).toRepresentation();
            realm.users().get(keycloakId).roles().realmLevel().add(List.of(role));
            log.info("Assigned role '{}' to Keycloak user '{}'", roleName, keycloakId);
        } catch (Exception exception) {
            log.error("Failed to assign role '{}' to Keycloak user '{}': {}", roleName, keycloakId, exception.getMessage());
            throw new BusinessException(ErrorCode.KEYCLOAK_ROLE_NOT_FOUND, roleName);
        }
    }

    private BusinessException userCreationUnavailable(Exception exception) {
        log.error("Keycloak user creation request failed", exception);
        return new BusinessException(
                ErrorCode.KEYCLOAK_USER_CREATION_FAILED,
                Response.Status.BAD_GATEWAY.getStatusCode(),
                "Keycloak administrative request failed"
        );
    }

    private MultiValueMap<String, String> clientCredentialsForm(String grantType) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", grantType);
        form.add("client_id", props.clientId());
        form.add("client_secret", props.clientSecret());
        return form;
    }

    private HttpEntity<MultiValueMap<String, String>> formRequest(MultiValueMap<String, String> form) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
        return new HttpEntity<>(form, headers);
    }

    private TokenResponse postToTokenEndpoint(MultiValueMap<String, String> form) {
        @SuppressWarnings("unchecked")
        ResponseEntity<Map<String, Object>> response =
                (ResponseEntity<Map<String, Object>>) (ResponseEntity<?>) restTemplate.postForEntity(
                        realmBaseUrl() + TOKEN_PATH,
                        formRequest(form),
                        Map.class
                );

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
        CredentialRepresentation credential = new CredentialRepresentation();
        credential.setType(CredentialRepresentation.PASSWORD);
        credential.setValue(password);
        credential.setTemporary(false);
        return credential;
    }

    private String realmBaseUrl() {
        return "%s/realms/%s".formatted(props.serverUrl(), props.realm());
    }

    private long toLong(Object value) {
        return value instanceof Number number ? number.longValue() : 0L;
    }
}
