package ma.onda.rag.agent.application;

import ma.onda.rag.agent.application.retrieval.OndaRetrievalPipeline;
import ma.onda.rag.agent.application.retrieval.RetrievalProfile;
import ma.onda.rag.agent.application.retrieval.RetrievalRequest;
import ma.onda.rag.agent.application.retrieval.RetrievalResult;
import ma.onda.rag.agent.infra.tools.VectorSearchTool;
import ma.onda.rag.conversation.api.ChatRequest;
import ma.onda.rag.conversation.application.ConversationService;
import ma.onda.rag.conversation.domain.MessageType;
import ma.onda.rag.conversation.infra.persistance.ChatMessageRepository;
import ma.onda.rag.document.infra.VectorMetadataKeys;
import ma.onda.rag.identity.infra.keycloak.SecurityUserContext;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.document.Document;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.tool.function.FunctionToolCallback;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class AgenticRagServiceTest {

    @Test
    void agentControlsRetrievalAndCitationsStayWithEachChatIncludingAfterFailure() {
        OndaRetrievalPipeline pipeline = mock(OndaRetrievalPipeline.class);
        Document document = Document.builder().text("evidence").score(0.9)
                .metadata(Map.of(VectorMetadataKeys.DOCUMENT_ID, "doc-1", VectorMetadataKeys.SOURCE, "guide.pdf")).build();
        when(pipeline.retrieve(new RetrievalRequest("CMN"))).thenReturn(RetrievalResult.fromDocuments(
                List.of(document), List.of("CMN"), RetrievalProfile.FAST, Map.of()));
        var callback = FunctionToolCallback.builder("vectorSearchTool", new VectorSearchTool(pipeline))
                .inputType(VectorSearchTool.Request.class).build();
        ChatModel model = mock(ChatModel.class);
        when(model.getOptions()).thenReturn(ToolCallingChatOptions.builder().build());
        AtomicInteger calls = new AtomicInteger();
        when(model.call(any(Prompt.class))).thenAnswer(invocation -> {
            int call = calls.incrementAndGet();
            if (call == 1 || call == 3) {
                Prompt prompt = invocation.getArgument(0);
                var options = (ToolCallingChatOptions) prompt.getOptions();
                // A worker thread must still contribute citations to the calling chat.
                try (var executor = Executors.newSingleThreadExecutor()) {
                    executor.submit(() -> options.getToolCallbacks().getFirst().call("{\"query\":\"CMN\"}",
                            new ToolContext(options.getToolContext()))).get();
                }
            }
            if (call == 3) {
                throw new IllegalStateException("model unavailable after retrieval");
            }
            return new ChatResponse(List.of(new Generation(new AssistantMessage("answer"))));
        });
        ConversationService conversations = mock(ConversationService.class);
        ChatMessageRepository messages = mock(ChatMessageRepository.class);
        when(messages.findByConversationIdOrderBySequenceNumberAsc(any())).thenReturn(List.of());
        AgenticRagService service = new AgenticRagService(ChatClient.builder(model).defaultTools(callback).build(),
                messages, conversations, mock(SecurityUserContext.class));
        ChatRequest request = new ChatRequest(UUID.randomUUID(), "question");

        var retrieved = service.executeChat(request);
        assertThat(retrieved.sources()).singleElement().satisfies(source -> {
            assertThat(source.type()).isEqualTo("DOCUMENT");
            assertThat(source.documentId()).isEqualTo("doc-1");
            assertThat(source.title()).isEqualTo("guide.pdf");
            assertThat(source.snippet()).isEqualTo("evidence");
            assertThat(source.relevanceScore()).isEqualTo(0.9);
        });
        assertThat(service.executeChat(request).sources()).isEmpty();
        assertThatThrownBy(() -> service.executeChat(request)).isInstanceOf(IllegalStateException.class);
        assertThat(service.executeChat(request).sources()).isEmpty();
        verify(pipeline, times(2)).retrieve(any());
        verify(conversations, times(3)).appendMessage(request.conversationId(), MessageType.ASSISTANT, "answer", null);
    }
}
