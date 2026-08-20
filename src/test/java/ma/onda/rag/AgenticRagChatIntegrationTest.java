package ma.onda.rag;

import ma.onda.rag.conversation.api.ChatRequest;
import ma.onda.rag.conversation.api.ChatResponse;
import ma.onda.rag.conversation.api.dto.ConversationResponse;
import ma.onda.rag.document.api.dto.DocumentResponse;
import ma.onda.rag.identity.api.dto.request.RegisterRequest;
import ma.onda.rag.identity.api.dto.response.TokenResponse;
import ma.onda.rag.shared.exception.ErrorResponse;
import ma.onda.rag.user.api.UserResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

public class AgenticRagChatIntegrationTest extends AbstractIntegrationTest {

    @Test
    @DisplayName("Should execute full end-to-end chat loop: Document ingestion -> Conversation creation -> Agentic RAG chat execution with vector search tool invocation -> Cited answer")
    void testFullChatLoopWithVectorSearchTool() throws Exception {
        String username = "rag_user_" + System.currentTimeMillis();
        String email = username + "@onda.ma";
        String password = "SecurePassword123!";

        UserResponse user = registerUser(username, email, password, "RAG", "Tester");
        grantIngestorRole(user.keycloakId());

        TokenResponse tokens = loginUser(username, password);

        // Step 1: Upload Knowledge Base Document
        String kbText = "ONDA Airport Security Manual 2026.\n" +
                "Rule 402: All visitor passes must be approved by the Airport Duty Manager.\n" +
                "Rule 403: Emergency evacuation points are designated at Terminal 1 Gate 4 and Terminal 2 Gate 8.";

        MockMultipartFile file = new MockMultipartFile(
                "file",
                "onda_security_manual.txt",
                MediaType.TEXT_PLAIN_VALUE,
                kbText.getBytes()
        );

        MvcResult docResult = mockMvc.perform(multipart("/api/v1/documents")
                        .file(file)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokens.accessToken()))
                .andExpect(status().isCreated())
                .andReturn();

        DocumentResponse doc = objectMapper.readValue(docResult.getResponse().getContentAsString(), DocumentResponse.class);
        assertThat(doc).isNotNull();

        // Step 2: Create Conversation
        MvcResult convResult = mockMvc.perform(post("/api/v1/conversations")
                        .param("title", "ONDA Safety Inquiry")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokens.accessToken()))
                .andExpect(status().isCreated())
                .andReturn();

        ConversationResponse conversation = objectMapper.readValue(convResult.getResponse().getContentAsString(), ConversationResponse.class);
        assertThat(conversation).isNotNull();

        // Step 3: Execute Agentic RAG Chat Execution
        ChatRequest chatRequest = new ChatRequest(conversation.id(), "Where are the emergency evacuation points?");

        MvcResult chatResult = mockMvc.perform(post("/api/v1/chat")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokens.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(chatRequest)))
                .andExpect(status().isOk())
                .andReturn();

        ChatResponse response = objectMapper.readValue(chatResult.getResponse().getContentAsString(), ChatResponse.class);

        assertThat(response).isNotNull();
        assertThat(response.answer()).isNotBlank();
        assertThat(response.sources()).isNotNull();
        assertThat(response.sources()).isNotEmpty();
        assertThat(response.sources().get(0).title()).isEqualTo("onda_security_manual.txt");
    }

    @Test
    @DisplayName("Should verify Actuator health endpoint returns UP and application exceptions map to RFC 7807 error schema")
    void testActuatorHealthCheckAndRfc7807ErrorResponseSchema() throws Exception {
        // 1. Actuator Health Indicator
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));

        // 2. Validation Exception RFC 7807 Schema (MethodArgumentNotValidException)
        RegisterRequest invalidRequest = new RegisterRequest("", "invalid-email", "", "", "");

        MvcResult validationResult = mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(invalidRequest)))
                .andExpect(status().isBadRequest())
                .andReturn();

        ErrorResponse errorResponse = objectMapper.readValue(validationResult.getResponse().getContentAsString(), ErrorResponse.class);

        assertThat(errorResponse).isNotNull();
        assertThat(errorResponse.status()).isEqualTo(400);
        assertThat(errorResponse.error()).isEqualTo("VALIDATION_FAILED");
        assertThat(errorResponse.message()).isNotBlank();
        assertThat(errorResponse.validationErrors()).isNotEmpty();

        // 3. Unauthorized Access RFC 7807 ProblemDetail response
        mockMvc.perform(get("/api/v1/conversations"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.title").value("Unauthorized"));
    }
}
