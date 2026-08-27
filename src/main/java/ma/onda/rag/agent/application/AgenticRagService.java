package ma.onda.rag.agent.application;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import ma.onda.rag.agent.application.retrieval.RetrievalResults;
import ma.onda.rag.agent.infra.tools.OndaWebsiteSearchTool;
import ma.onda.rag.agent.infra.tools.WebSearchTool;
import ma.onda.rag.conversation.api.ChatRequest;
import ma.onda.rag.conversation.api.ChatResponse;
import ma.onda.rag.conversation.api.CitedSourceDTO;
import ma.onda.rag.conversation.api.TokenUsageDTO;
import ma.onda.rag.conversation.application.ConversationService;
import ma.onda.rag.conversation.domain.ChatMessage;
import ma.onda.rag.conversation.domain.MessageType;
import ma.onda.rag.conversation.infra.persistance.ChatMessageRepository;
import ma.onda.rag.identity.infra.keycloak.SecurityUserContext;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class AgenticRagService {

    private final ChatClient chatClient;
    private final ChatMessageRepository chatMessageRepository;
    private final ConversationService conversationService;
    private final SecurityUserContext securityUserContext;

    @Transactional
    public ChatResponse executeChat(ChatRequest request) {
        persistUserMessage(request);
        
        List<Message> messages = loadChatHistory(request.conversationId());
        
        RetrievalResults retrievalResults = new RetrievalResults();
        WebSearchTool.clearLastResults();
        OndaWebsiteSearchTool.clearLastResults();

        try {
            org.springframework.ai.chat.model.ChatResponse aiResponse = callLLM(messages, retrievalResults);
            String answer = aiResponse.getResult().getOutput().getText();

            persistAssistantResponse(request.conversationId(), answer);

            TokenUsageDTO tokenUsage = extractTokenUsage(aiResponse);
            List<CitedSourceDTO> sources = extractCitedSources(retrievalResults);

            return new ChatResponse(answer, sources, tokenUsage);
        } finally {
            WebSearchTool.clearLastResults();
            OndaWebsiteSearchTool.clearLastResults();
        }
    }

    private void persistUserMessage(ChatRequest request) {
        // Appending the user message first will implicitly validate ownership
        // via ConversationService which throws an exception if the conversation is not owned
        conversationService.appendMessage(request.conversationId(), MessageType.USER, request.message(), null);
    }

    private List<Message> loadChatHistory(UUID conversationId) {
        List<ChatMessage> history = chatMessageRepository.findByConversationIdOrderBySequenceNumberAsc(conversationId);
        List<Message> messages = new ArrayList<>();
        for (ChatMessage msg : history) {
            if (msg.getMessageType() == MessageType.USER) {
                messages.add(new UserMessage(msg.getContent()));
            } else if (msg.getMessageType() == MessageType.ASSISTANT) {
                messages.add(new AssistantMessage(msg.getContent()));
            } else if (msg.getMessageType() == MessageType.SYSTEM) {
                messages.add(new SystemMessage(msg.getContent()));
            }
        }
        return messages;
    }

    private org.springframework.ai.chat.model.ChatResponse callLLM(List<Message> messages, RetrievalResults retrievalResults) {
        return chatClient.prompt()
                .messages(messages)
                .toolContext(Map.of(RetrievalResults.TOOL_CONTEXT_KEY, retrievalResults))
                .call()
                .chatResponse();
    }

    private void persistAssistantResponse(java.util.UUID conversationId, String answer) {
        conversationService.appendMessage(conversationId, MessageType.ASSISTANT, answer, null);
    }

    private TokenUsageDTO extractTokenUsage(org.springframework.ai.chat.model.ChatResponse aiResponse) {
        if (aiResponse.getMetadata() != null && aiResponse.getMetadata().getUsage() != null) {
            var usage = aiResponse.getMetadata().getUsage();
            return new TokenUsageDTO(
                    usage.getPromptTokens() != null ? usage.getPromptTokens().longValue() : 0L,
                    usage.getCompletionTokens() != null ? usage.getCompletionTokens().longValue() : 0L,
                    usage.getTotalTokens() != null ? usage.getTotalTokens().longValue() : 0L
            );
        }
        return null;
    }

    private List<CitedSourceDTO> extractCitedSources(RetrievalResults retrievalResults) {
        List<CitedSourceDTO> sources = new ArrayList<>();
        retrievalResults.snapshot().stream().flatMap(result -> result.sources().stream())
                .forEach(source -> sources.add(new CitedSourceDTO("DOCUMENT", source.documentId(),
                        source.sourceFilename(), null, source.chunkContent(), source.relevanceScore())));
        List<WebSearchTool.WebSearchSnippet> generalWebResults = WebSearchTool.getLastResults();
        if (generalWebResults != null) {
            generalWebResults.forEach(result -> sources.add(new CitedSourceDTO(
                    "WEB", null, result.title(), result.url(), result.content(), result.relevanceScore())));
        }
        List<OndaWebsiteSearchTool.WebSearchResult> ondaWebResults = OndaWebsiteSearchTool.getLastResults();
        if (ondaWebResults != null) {
            ondaWebResults.forEach(result -> sources.add(new CitedSourceDTO(
                    "ONDA_WEB", null, result.title(), result.url(), result.content(), result.relevanceScore())));
        }
        return sources;
    }
}
