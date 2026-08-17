package ma.onda.rag.identity.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import ma.onda.rag.identity.api.dto.request.ChangePasswordRequest;
import ma.onda.rag.identity.api.dto.request.LoginRequest;
import ma.onda.rag.identity.api.dto.request.LogoutRequest;
import ma.onda.rag.identity.api.dto.request.RefreshTokenRequest;
import ma.onda.rag.identity.api.dto.request.RegisterRequest;
import ma.onda.rag.identity.api.dto.response.TokenResponse;
import ma.onda.rag.identity.application.KeycloakAdminClientService;
import ma.onda.rag.identity.application.RegistrationService;
import ma.onda.rag.identity.infra.keycloak.SecurityUserContext;
import ma.onda.rag.shared.handler.GlobalExceptionHandler;
import ma.onda.rag.user.api.UserResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.LocalDateTime;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Standalone MockMvc tests for {@link AuthController}.
 *
 * <p>No Spring context is loaded — dependencies are provided as Mockito mocks,
 * wired into the controller via {@link MockMvcBuilders#standaloneSetup}.
 */
@ExtendWith(MockitoExtension.class)
class AuthControllerTest {

    private MockMvc mockMvc;
    private ObjectMapper objectMapper;

    @Mock
    private RegistrationService registrationService;

    @Mock
    private KeycloakAdminClientService keycloakAdminClientService;

    @Mock
    private SecurityUserContext securityUserContext;

    @InjectMocks
    private AuthController authController;

    private static final String KC_ID = "kc-uuid-abc123";

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        mockMvc = MockMvcBuilders
                .standaloneSetup(authController)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    // -----------------------------------------------------------------------
    // POST /api/v1/auth/register
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("POST /register with valid body should return 201 with UserResponse")
    void register_validRequest_returns201WithUserResponse() throws Exception {
        RegisterRequest request = new RegisterRequest(
                "b_naji", "b.naji@onda.ma", "SecureP@ss1!", "Brahim", "Naji");

        UserResponse response = new UserResponse(
                UUID.randomUUID(), KC_ID, "b_naji", "b.naji@onda.ma",
                "Brahim", "Naji", LocalDateTime.now());

        when(registrationService.register(any(RegisterRequest.class))).thenReturn(response);

        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.username").value("b_naji"))
                .andExpect(jsonPath("$.email").value("b.naji@onda.ma"))
                .andExpect(jsonPath("$.keycloakId").value(KC_ID));
    }

    @Test
    @DisplayName("POST /register with blank username should return 400 ErrorResponse")
    void register_blankUsername_returns400() throws Exception {
        RegisterRequest badRequest = new RegisterRequest(
                "", "b.naji@onda.ma", "SecureP@ss1!", "Brahim", "Naji");

        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(badRequest)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("VALIDATION_FAILED"));

        verifyNoInteractions(registrationService);
    }

    @Test
    @DisplayName("POST /register with invalid email should return 400 ErrorResponse")
    void register_invalidEmail_returns400() throws Exception {
        RegisterRequest badRequest = new RegisterRequest(
                "b_naji", "not-an-email", "SecureP@ss1!", "Brahim", "Naji");

        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(badRequest)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400));

        verifyNoInteractions(registrationService);
    }

    // -----------------------------------------------------------------------
    // POST /api/v1/auth/login
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("POST /login with valid credentials should return 200 with TokenResponse")
    void login_validCredentials_returns200WithTokens() throws Exception {
        LoginRequest request = new LoginRequest("b_naji", "SecureP@ss1!");
        TokenResponse tokens = new TokenResponse(
                "access-jwt", "refresh-jwt", "Bearer", 300L, 1800L);

        when(keycloakAdminClientService.obtainTokens("b_naji", "SecureP@ss1!")).thenReturn(tokens);

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.access_token").value("access-jwt"))
                .andExpect(jsonPath("$.refresh_token").value("refresh-jwt"))
                .andExpect(jsonPath("$.token_type").value("Bearer"))
                .andExpect(jsonPath("$.expires_in").value(300));
    }

    @Test
    @DisplayName("POST /login with blank password should return 400 ProblemDetail")
    void login_blankPassword_returns400() throws Exception {
        LoginRequest bad = new LoginRequest("b_naji", "");

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(bad)))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(keycloakAdminClientService);
    }

    // -----------------------------------------------------------------------
    // POST /api/v1/auth/refresh
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("POST /refresh with valid refresh token should return 200 with new TokenResponse")
    void refresh_validToken_returns200WithTokens() throws Exception {
        RefreshTokenRequest request = new RefreshTokenRequest("valid-refresh-token");
        TokenResponse tokens = new TokenResponse(
                "new-access-jwt", "new-refresh-jwt", "Bearer", 300L, 1800L);

        when(keycloakAdminClientService.refreshToken("valid-refresh-token")).thenReturn(tokens);

        mockMvc.perform(post("/api/v1/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.access_token").value("new-access-jwt"));
    }

    @Test
    @DisplayName("POST /refresh with blank refresh token should return 400")
    void refresh_blankToken_returns400() throws Exception {
        RefreshTokenRequest bad = new RefreshTokenRequest("");

        mockMvc.perform(post("/api/v1/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(bad)))
                .andExpect(status().isBadRequest());
    }

    // -----------------------------------------------------------------------
    // POST /api/v1/auth/logout
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("POST /logout with valid body should return 200")
    void logout_validToken_returns200() throws Exception {
        LogoutRequest request = new LogoutRequest("valid-refresh-token");
        when(securityUserContext.getCurrentKeycloakId()).thenReturn(KC_ID);
        doNothing().when(keycloakAdminClientService).logout("valid-refresh-token");

        mockMvc.perform(post("/api/v1/auth/logout")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk());

        verify(keycloakAdminClientService).logout("valid-refresh-token");
    }

    // -----------------------------------------------------------------------
    // PUT /api/v1/auth/change-password
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("PUT /change-password with valid body should return 200")
    void changePassword_validBody_returns200() throws Exception {
        ChangePasswordRequest request = new ChangePasswordRequest("NewP@ss1!");
        when(securityUserContext.getCurrentKeycloakId()).thenReturn(KC_ID);
        doNothing().when(keycloakAdminClientService).changePassword(KC_ID, "NewP@ss1!");

        mockMvc.perform(put("/api/v1/auth/change-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk());

        verify(keycloakAdminClientService).changePassword(KC_ID, "NewP@ss1!");
    }

    @Test
    @DisplayName("PUT /change-password with blank password should return 400")
    void changePassword_blankPassword_returns400() throws Exception {
        ChangePasswordRequest bad = new ChangePasswordRequest("");

        mockMvc.perform(put("/api/v1/auth/change-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(bad)))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(keycloakAdminClientService);
    }
}
