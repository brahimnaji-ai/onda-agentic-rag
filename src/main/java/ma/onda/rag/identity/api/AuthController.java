package ma.onda.rag.identity.api;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import ma.onda.rag.identity.api.dto.request.ChangePasswordRequest;
import ma.onda.rag.identity.api.dto.request.LoginRequest;
import ma.onda.rag.identity.api.dto.request.LogoutRequest;
import ma.onda.rag.identity.api.dto.request.RefreshTokenRequest;
import ma.onda.rag.identity.api.dto.request.RegisterRequest;
import ma.onda.rag.identity.api.dto.response.TokenResponse;
import ma.onda.rag.identity.application.KeycloakAdminClientService;
import ma.onda.rag.identity.application.RegistrationService;
import ma.onda.rag.identity.infra.keycloak.SecurityUserContext;
import ma.onda.rag.user.api.UserResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Authentication and identity management endpoints.
 *
 * <table border="1">
 *   <tr><th>Method</th><th>Path</th><th>Auth</th><th>Returns</th></tr>
 *   <tr><td>POST</td><td>/api/v1/auth/register</td><td>public</td><td>201 UserResponse</td></tr>
 *   <tr><td>POST</td><td>/api/v1/auth/login</td><td>public</td><td>200 TokenResponse</td></tr>
 *   <tr><td>POST</td><td>/api/v1/auth/refresh</td><td>public</td><td>200 TokenResponse</td></tr>
 *   <tr><td>POST</td><td>/api/v1/auth/logout</td><td>ROLE_USER</td><td>200</td></tr>
 *   <tr><td>PUT</td><td>/api/v1/auth/change-password</td><td>ROLE_USER</td><td>200</td></tr>
 * </table>
 *
 * <p>Validation errors return HTTP 400 with an RFC 7807 {@code ProblemDetail}
 * body, handled by Spring's built-in {@code DefaultHandlerExceptionResolver} in
 * combination with the project's {@link ma.onda.rag.shared.handler.GlobalExceptionHandler}.
 */


@RestController
@Slf4j
@RequiredArgsConstructor
@RequestMapping("/api/v1/auth")
public class AuthController {

    private final RegistrationService registrationService;
    private final KeycloakAdminClientService keycloakAdminClientService;
    private final SecurityUserContext securityUserContext;

    // Public endpoints

    /**
     * Registers a new user via the dual-write saga (Keycloak + PostgreSQL).
     *
     * @param request validated registration payload
     * @return 201 Created with the new user's profile
     */

    @PostMapping("/register")
    public ResponseEntity<UserResponse> register(
            @RequestBody @Valid RegisterRequest request
    ) {
        log.info("Registration request for username='{}'", request.username());

        UserResponse response = registrationService.register(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    /**
     * Authenticates a user and returns Keycloak JWT tokens.
     *
     * @param request validated login credentials
     * @return 200 OK with access + refresh tokens
     */

    @PostMapping("/login")
    public ResponseEntity<TokenResponse> login(
            @RequestBody @Valid LoginRequest request
    ) {
        log.info("Login request for username='{}'", request.username());

        TokenResponse tokens = keycloakAdminClientService.obtainTokens(
                request.username(), request.password());
        return ResponseEntity.ok(tokens);
    }

    /**
     * Refreshes an expired access token using the provided refresh token.
     *
     * @param request body containing the refresh token
     * @return 200 OK with a new pair of tokens
     */

    @PostMapping("/refresh")
    public ResponseEntity<TokenResponse> refresh(
            @RequestBody @Valid RefreshTokenRequest request
    ) {
        log.debug("Token refresh request");

        TokenResponse tokens = keycloakAdminClientService.refreshToken(request.refreshToken());
        return ResponseEntity.ok(tokens);
    }

    // Authenticated endpoints

    /**
     * Revokes the user's refresh token, invalidating the Keycloak session.
     *
     * @param request body containing the refresh token to revoke
     * @return 200 OK
     */

    @PostMapping("/logout")
    @PreAuthorize("hasRole('USER')")
    public ResponseEntity<Void> logout(
            @RequestBody @Valid LogoutRequest request
    ) {
        log.info("Logout request for keycloakId='{}'", securityUserContext.getCurrentKeycloakId());

        keycloakAdminClientService.logout(request.refreshToken());
        return ResponseEntity.ok().build();
    }

    /**
     * Changes the password for the currently authenticated user.
     *
     * @param request body containing the new password
     * @return 200 OK
     */

    @PutMapping("/change-password")
    @PreAuthorize("hasRole('USER')")
    public ResponseEntity<Void> changePassword(
            @RequestBody @Valid ChangePasswordRequest request
    ) {
        String keycloakId = securityUserContext.getCurrentKeycloakId();

        log.info("Change-password request for keycloakId='{}'", keycloakId);

        keycloakAdminClientService.changePassword(keycloakId, request.newPassword());
        return ResponseEntity.ok().build();
    }
}
