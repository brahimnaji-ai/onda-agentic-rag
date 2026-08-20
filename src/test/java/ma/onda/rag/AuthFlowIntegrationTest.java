package ma.onda.rag;

import ma.onda.rag.identity.api.dto.request.LoginRequest;
import ma.onda.rag.identity.api.dto.request.LogoutRequest;
import ma.onda.rag.identity.api.dto.request.RefreshTokenRequest;
import ma.onda.rag.identity.api.dto.request.RegisterRequest;
import ma.onda.rag.identity.api.dto.response.TokenResponse;
import ma.onda.rag.user.api.UserResponse;
import ma.onda.rag.user.domain.User;
import ma.onda.rag.user.infra.UserRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.keycloak.representations.idm.UserRepresentation;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

public class AuthFlowIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private UserRepository userRepository;

    @Test
    @DisplayName("Should successfully execute dual-write user registration (Keycloak + PostgreSQL)")
    void testRegistrationDualWrite() throws Exception {
        String username = "testuser_reg_" + System.currentTimeMillis();
        String email = username + "@onda.ma";

        RegisterRequest request = new RegisterRequest(
                username,
                email,
                "Password123!",
                "John",
                "Doe"
        );

        MvcResult result = mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andReturn();

        UserResponse userResponse = objectMapper.readValue(result.getResponse().getContentAsString(), UserResponse.class);

        assertThat(userResponse).isNotNull();
        assertThat(userResponse.uuid()).isNotNull();
        assertThat(userResponse.keycloakId()).isNotBlank();
        assertThat(userResponse.username()).isEqualTo(username);
        assertThat(userResponse.email()).isEqualTo(email);

        // Verify local PostgreSQL database persistence
        Optional<User> dbUser = userRepository.findByKeycloakId(userResponse.keycloakId());
        assertThat(dbUser).isPresent();
        assertThat(dbUser.get().getUsername()).isEqualTo(username);

        // Verify Keycloak user creation
        UserRepresentation kcUser = keycloakAdminClient.realm("onda-rag-realm")
                .users().get(userResponse.keycloakId()).toRepresentation();
        assertThat(kcUser).isNotNull();
        assertThat(kcUser.getUsername()).isEqualTo(username);
    }

    @Test
    @DisplayName("Should execute token retrieval, token refresh, and session logout against Keycloak container")
    void testTokenRetrievalRefreshAndLogout() throws Exception {
        String username = "testuser_flow_" + System.currentTimeMillis();
        String email = username + "@onda.ma";
        String password = "SecurePassword123!";

        registerUser(username, email, password, "Flow", "Tester");

        // 1. Token Retrieval (Login)
        LoginRequest loginRequest = new LoginRequest(username, password);
        MvcResult loginResult = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(loginRequest)))
                .andExpect(status().isOk())
                .andReturn();

        TokenResponse tokenResponse = objectMapper.readValue(loginResult.getResponse().getContentAsString(), TokenResponse.class);
        assertThat(tokenResponse).isNotNull();
        assertThat(tokenResponse.accessToken()).isNotBlank();
        assertThat(tokenResponse.refreshToken()).isNotBlank();
        assertThat(tokenResponse.tokenType()).isEqualTo("Bearer");

        // 2. Token Refresh
        RefreshTokenRequest refreshRequest = new RefreshTokenRequest(tokenResponse.refreshToken());
        MvcResult refreshResult = mockMvc.perform(post("/api/v1/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(refreshRequest)))
                .andExpect(status().isOk())
                .andReturn();

        TokenResponse refreshedTokens = objectMapper.readValue(refreshResult.getResponse().getContentAsString(), TokenResponse.class);
        assertThat(refreshedTokens).isNotNull();
        assertThat(refreshedTokens.accessToken()).isNotBlank();
        assertThat(refreshedTokens.refreshToken()).isNotBlank();

        // 3. Session Logout
        LogoutRequest logoutRequest = new LogoutRequest(refreshedTokens.refreshToken());
        mockMvc.perform(post("/api/v1/auth/logout")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + refreshedTokens.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(logoutRequest)))
                .andExpect(status().isOk());
    }
}
