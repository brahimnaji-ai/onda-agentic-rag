package ma.onda.rag.shared.security;

import ma.onda.rag.identity.infra.keycloak.KeycloakJwtAuthenticationConverter;
import ma.onda.rag.shared.handler.GlobalExceptionHandler;
import ma.onda.rag.user.api.UserController;
import ma.onda.rag.user.api.UserResponse;
import ma.onda.rag.user.application.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.annotation.AnnotatedBeanDefinitionReader;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockServletContext;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.support.GenericWebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

import java.time.LocalDateTime;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class SecurityConfigTest {

    private MockMvc mockMvc;

    @Mock
    private UserService userService;

    @Mock
    private JwtDecoder jwtDecoder;

    @Configuration
    @EnableWebMvc
    static class TestMvcConfig {}

    @RestController
    @RequestMapping("/api/v1/admin")
    static class TestAdminController {
        @GetMapping("/dashboard")
        @PreAuthorize("hasRole('ADMIN')")
        public String getAdminDashboard() {
            return "Admin Data";
        }
    }

    @BeforeEach
    void setUp() {
        GenericWebApplicationContext context = new GenericWebApplicationContext();
        context.setServletContext(new MockServletContext());
        AnnotatedBeanDefinitionReader reader = new AnnotatedBeanDefinitionReader(context);
        reader.register(TestMvcConfig.class, SecurityConfig.class, KeycloakJwtAuthenticationConverter.class, UserController.class, TestAdminController.class, GlobalExceptionHandler.class);
        context.registerBean(UserService.class, () -> userService);
        context.registerBean(JwtDecoder.class, () -> jwtDecoder);
        context.refresh();

        mockMvc = MockMvcBuilders.webAppContextSetup(context)
                .apply(SecurityMockMvcConfigurers.springSecurity())
                .build();
    }

    @Test
    @DisplayName("Unauthenticated request to /api/v1/users/me should return HTTP 401 with RFC 7807 ProblemDetails")
    void unauthenticatedRequest_shouldReturn401ProblemDetails() throws Exception {
        mockMvc.perform(get("/api/v1/users/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE))
                .andExpect(jsonPath("$.title").value("Unauthorized"))
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.detail").exists())
                .andExpect(jsonPath("$.instance").value("/api/v1/users/me"));
    }

    @Test
    @DisplayName("Public endpoint /api/v1/auth/login should be permitted without authentication")
    void publicEndpoint_shouldBePermitted() throws Exception {
        mockMvc.perform(get("/api/v1/auth/login"))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("Authenticated request with valid JWT should access protected endpoint /api/v1/users/me")
    void authenticatedRequest_shouldSucceed() throws Exception {
        UserResponse mockResponse = new UserResponse(
                UUID.randomUUID(),
                "kc-sub-123",
                "b.naji",
                "b.naji@onda.ma",
                "Brahim",
                "Naji",
                LocalDateTime.now()
        );

        when(userService.getProfileByKeycloakId(anyString())).thenReturn(mockResponse);

        mockMvc.perform(get("/api/v1/users/me")
                        .with(jwt().jwt(builder -> builder
                                .tokenValue("mock-jwt-token")
                                .subject("kc-sub-123")
                                .claim("preferred_username", "b.naji"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value("b.naji"))
                .andExpect(jsonPath("$.email").value("b.naji@onda.ma"));
    }

    @Test
    @DisplayName("Request to @PreAuthorize('hasRole(\"ADMIN\")') endpoint without ADMIN role should return 403 Forbidden")
    void adminEndpoint_withoutAdminRole_shouldReturn403() throws Exception {
        mockMvc.perform(get("/api/v1/admin/dashboard")
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_USER"))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").value(403))
                .andExpect(jsonPath("$.error").value("FORBIDDEN"));
    }

    @Test
    @DisplayName("Request to @PreAuthorize('hasRole(\"ADMIN\")') endpoint with ADMIN role should return 200 OK")
    void adminEndpoint_withAdminRole_shouldSucceed() throws Exception {
        mockMvc.perform(get("/api/v1/admin/dashboard")
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))))
                .andExpect(status().isOk())
                .andExpect(content().string("Admin Data"));
    }
}
