package ma.onda.rag;

import com.fasterxml.jackson.databind.ObjectMapper;
import dasniko.testcontainers.keycloak.KeycloakContainer;
import ma.onda.rag.agent.infra.tools.VectorSearchTool;
import ma.onda.rag.identity.api.dto.request.LoginRequest;
import ma.onda.rag.identity.api.dto.request.RegisterRequest;
import ma.onda.rag.identity.api.dto.response.TokenResponse;
import ma.onda.rag.identity.application.RegistrationService;
import ma.onda.rag.user.api.UserResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.TestInstance;
import org.keycloak.admin.client.Keycloak;
import org.keycloak.representations.idm.RoleRepresentation;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, classes = {OndaRagApplication.class, AbstractIntegrationTest.IntegrationTestAiConfig.class})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public abstract class AbstractIntegrationTest {

    protected static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(DockerImageName.parse("pgvector/pgvector:pg17"))
            .withDatabaseName("onda_rag_db")
            .withUsername("onda_user")
            .withPassword("onda_password");

    // Keep Testcontainers on the same Keycloak release exercised by Docker Compose.
    protected static final KeycloakContainer keycloak = new KeycloakContainer("quay.io/keycloak/keycloak:26.7.1")
            .withEnv("KEYCLOAK_CLIENT_SECRET", "PbFRHqc2lOgQSsAWmLNSd9h8kklsSwuE")
            .withRealmImportFile("docker/keycloak/onda-rag-realm-export.json");

    static {
        postgres.start();
        keycloak.start();
    }

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "create-drop");
        registry.add("spring.jpa.properties.hibernate.dialect", () -> "org.hibernate.dialect.PostgreSQLDialect");

        registry.add("keycloak.server-url", () -> getAuthUrl());
        registry.add("keycloak.realm", () -> "onda-rag-realm");
        registry.add("keycloak.client-id", () -> "onda-rag-api");
        registry.add("keycloak.client-secret", () -> "PbFRHqc2lOgQSsAWmLNSd9h8kklsSwuE");
        registry.add("spring.security.oauth2.resourceserver.jwt.issuer-uri", () -> getAuthUrl() + "/realms/onda-rag-realm");
        registry.add("spring.security.oauth2.client.provider.keycloak-admin.token-uri", () -> getAuthUrl() + "/realms/onda-rag-realm/protocol/openid-connect/token");

        registry.add("spring.ai.google.genai.api-key", () -> "dummy-key");
        registry.add("spring.ai.google.genai.embedding.api-key", () -> "dummy-key");
        registry.add("tavily.api-key", () -> "dummy-tavily-key");
    }

    private static String getAuthUrl() {
        String raw = keycloak.getAuthServerUrl();
        return raw.endsWith("/") ? raw.substring(0, raw.length() - 1) : raw;
    }

    @Autowired
    protected WebApplicationContext webApplicationContext;

    protected MockMvc mockMvc;

    @Autowired(required = false)
    protected ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    @Autowired
    protected RegistrationService registrationService;

    @Autowired
    protected Keycloak keycloakAdminClient;

    @Autowired
    protected VectorSearchTool vectorSearchTool;

    @BeforeEach
    public void setupMockMvc() {
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext)
                .apply(SecurityMockMvcConfigurers.springSecurity())
                .build();
    }

    @TestConfiguration
    public static class IntegrationTestAiConfig {

        @Bean
        @Primary
        public ObjectMapper objectMapper() {
            return new ObjectMapper().findAndRegisterModules();
        }

        @Bean
        @Primary
        public EmbeddingModel embeddingModel() {
            EmbeddingModel model = mock(EmbeddingModel.class);
            float[] vector768 = new float[768];
            for (int i = 0; i < 768; i++) {
                vector768[i] = 0.1f;
            }
            when(model.dimensions()).thenReturn(768);
            when(model.embed(any(Document.class))).thenReturn(vector768);
            when(model.embed(any(String.class))).thenReturn(vector768);
            when(model.embed(anyList())).thenAnswer(invocation -> {
                List<?> list = invocation.getArgument(0);
                return list.stream().map(item -> vector768).toList();
            });
            when(model.embed(anyList(), any(), any())).thenAnswer(invocation -> {
                List<?> list = invocation.getArgument(0);
                return list.stream().map(item -> vector768).toList();
            });
            when(model.call(any(EmbeddingRequest.class))).thenAnswer(invocation -> {
                EmbeddingRequest req = invocation.getArgument(0);
                List<Embedding> list = java.util.stream.IntStream.range(0, req.getInstructions().size())
                        .mapToObj(i -> new Embedding(vector768, i))
                        .toList();
                return new EmbeddingResponse(list);
            });
            return model;
        }

        @Bean
        @Primary
        public ChatModel chatModel(VectorSearchTool vectorSearchTool) {
            ChatModel model = mock(ChatModel.class);
            org.springframework.ai.chat.prompt.ChatOptions defaultOptions = org.springframework.ai.chat.prompt.ChatOptions.builder().build();
            when(model.getOptions()).thenReturn(defaultOptions);
            when(model.getDefaultOptions()).thenReturn(defaultOptions);
            when(model.call(any(Prompt.class))).thenAnswer(invocation -> {
                vectorSearchTool.apply(new VectorSearchTool.Request("test query"));
                AssistantMessage assistantMessage = new AssistantMessage("Based on official ONDA documents, security policy requires double authentication.");
                Generation generation = new Generation(assistantMessage);
                return new ChatResponse(List.of(generation));
            });
            return model;
        }
    }

    protected UserResponse registerUser(String username, String email, String password, String firstName, String lastName) {
        RegisterRequest request = new RegisterRequest(username, email, password, firstName, lastName);
        return registrationService.register(request);
    }

    protected void grantIngestorRole(String keycloakId) {
        RoleRepresentation role = keycloakAdminClient.realm("onda-rag-realm")
                .roles().get("INGESTOR").toRepresentation();
        keycloakAdminClient.realm("onda-rag-realm")
                .users().get(keycloakId).roles().realmLevel().add(List.of(role));
    }

    protected TokenResponse loginUser(String username, String password) throws Exception {
        LoginRequest loginRequest = new LoginRequest(username, password);
        MvcResult result = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(loginRequest)))
                .andExpect(status().isOk())
                .andReturn();

        return objectMapper.readValue(result.getResponse().getContentAsString(), TokenResponse.class);
    }

    protected HttpHeaders authHeaders(String accessToken) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(accessToken);
        return headers;
    }
}
