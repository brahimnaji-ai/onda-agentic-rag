package ma.onda.rag;

import ma.onda.rag.conversation.api.ChatRequest;
import ma.onda.rag.conversation.api.dto.ConversationResponse;
import ma.onda.rag.identity.api.dto.response.TokenResponse;
import ma.onda.rag.user.api.UserResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

public class ConversationBolaIntegrationTest extends AbstractIntegrationTest {

    @Test
    @DisplayName("Should enforce BOLA security constraints: User B token requesting User A conversation resources must return 403 or 404")
    void testBolaIsolationBetweenUserTokens() throws Exception {
        // Register User A
        String userA_name = "usera_" + System.currentTimeMillis();
        UserResponse userA = registerUser(userA_name, userA_name + "@onda.ma", "Password123!", "User", "A");
        TokenResponse tokensA = loginUser(userA_name, "Password123!");

        // Register User B
        String userB_name = "userb_" + System.currentTimeMillis();
        UserResponse userB = registerUser(userB_name, userB_name + "@onda.ma", "Password123!", "User", "B");
        TokenResponse tokensB = loginUser(userB_name, "Password123!");

        // User A creates a conversation
        MvcResult createResult = mockMvc.perform(post("/api/v1/conversations")
                        .param("title", "User A Private Conversation")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokensA.accessToken()))
                .andExpect(status().isCreated())
                .andReturn();

        ConversationResponse convA = objectMapper.readValue(createResult.getResponse().getContentAsString(), ConversationResponse.class);
        UUID convIdA = convA.id();

        // 1. User B attempts to access User A's conversation details -> expect 403 or 404
        int getStatusCode = mockMvc.perform(get("/api/v1/conversations/" + convIdA)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokensB.accessToken()))
                .andReturn().getResponse().getStatus();

        assertThat(getStatusCode).isIn(403, 404);

        // 2. User B attempts to delete User A's conversation -> expect 403 or 404
        int deleteStatusCode = mockMvc.perform(delete("/api/v1/conversations/" + convIdA)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokensB.accessToken()))
                .andReturn().getResponse().getStatus();

        assertThat(deleteStatusCode).isIn(403, 404);

        // 3. User B attempts to send chat message to User A's conversation -> expect 403 or 404
        ChatRequest unauthorizedChatRequest = new ChatRequest(convIdA, "Can I see User A messages?");
        int chatStatusCode = mockMvc.perform(post("/api/v1/chat")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokensB.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(unauthorizedChatRequest)))
                .andReturn().getResponse().getStatus();

        assertThat(chatStatusCode).isIn(403, 404);

        // 4. Verify User A can access their own conversation
        mockMvc.perform(get("/api/v1/conversations/" + convIdA)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokensA.accessToken()))
                .andExpect(status().isOk());
    }
}
