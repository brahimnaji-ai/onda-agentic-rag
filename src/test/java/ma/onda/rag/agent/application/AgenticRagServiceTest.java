package ma.onda.rag.agent.application;

import ma.onda.rag.agent.application.retrieval.OndaRetrievalPipeline;
import ma.onda.rag.agent.application.retrieval.RetrievalProfile;
import ma.onda.rag.agent.application.retrieval.RetrievalRequest;
import ma.onda.rag.agent.application.retrieval.RetrievalResult;
import ma.onda.rag.agent.application.retrieval.RetrievalStage;
import ma.onda.rag.agent.infra.retrieval.HybridDocumentRetriever;
import ma.onda.rag.agent.infra.tools.VectorSearchTool;
import ma.onda.rag.agent.infra.tools.WebSearchTool;
import ma.onda.rag.conversation.api.ChatController;
import ma.onda.rag.conversation.api.ChatRequest;
import ma.onda.rag.conversation.api.SourceRetrievalDTO;
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
import org.springframework.ai.rag.Query;
import org.springframework.ai.tool.function.FunctionToolCallback;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class AgenticRagServiceTest {

    @Test
    void mapsRerankerAndRecallScoresSeparatelyWithoutLosingCitationMetadata() {
        var document = chunk("a", 0.03).mutate().metadata(Map.of("document_id", "doc-a", "source", "a.pdf",
                "retrieval", Map.of("rrf_score", 0.03, "rrf_k", 60, "dense_rank", 1, "dense_score", 0.8,
                        "lexical_rank", 2, "lexical_score", 7.0))).build();
        var processor = new ma.onda.rag.agent.infra.springai.OndaDocumentPostProcessor(
                new ma.onda.rag.agent.infra.springai.PostRetrievalProperties(4),
                (q, candidates) -> new ma.onda.rag.agent.application.retrieval.DocumentReranker.Result(
                        Map.of("a", 0.95), ma.onda.rag.agent.application.retrieval.DocumentReranker.Status.SUCCESS),
                (q, anchor) -> List.of());
        var selected = processor.process(new Query("q"), List.of(document));
        var evidence = ChatRetrievalMapper.map(List.of(RetrievalResult.fromDocuments(selected, List.of("q"),
                RetrievalProfile.BALANCED, Map.of())));
        var source = evidence.sources().getFirst();
        assertThat(source.documentId()).isEqualTo("doc-a");
        assertThat(source.relevanceScore()).isEqualTo(0.03);
        assertThat(source.retrieval().scoreType()).isEqualTo(SourceRetrievalDTO.ScoreType.RRF);
        assertThat(source.retrieval().rrfScore()).isEqualTo(0.03);
        assertThat(source.retrieval().dense()).isEqualTo(new SourceRetrievalDTO.Arm(1, 0.8));
        assertThat(source.retrieval().lexical()).isEqualTo(new SourceRetrievalDTO.Arm(2, 7.0));
        assertThat(source.retrieval().rerankerScore()).isEqualTo(0.95);
        assertThat(source.retrieval().rerankerStatus()).isEqualTo("SUCCESS");
        assertThat(source.retrieval().contextTokens()).isPositive();
    }

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
            assertThat(source.retrieval().executionId()).isEqualTo(1);
            assertThat(source.retrieval().chunkId()).isEqualTo(document.getId());
            assertThat(source.retrieval().profile()).isEqualTo(RetrievalProfile.FAST);
            assertThat(source.retrieval().scoreType()).isEqualTo(SourceRetrievalDTO.ScoreType.COSINE_SIMILARITY);
            assertThat(source.retrieval().dense()).isEqualTo(new SourceRetrievalDTO.Arm(null, 0.9));
            assertThat(source.retrieval().lexical()).isNull();
            assertThat(source.retrieval().rrfK()).isNull();
        });
        assertThat(retrieved.retrievals()).singleElement().satisfies(execution -> {
            assertThat(execution.id()).isEqualTo(1);
            assertThat(execution.profile()).isEqualTo(RetrievalProfile.FAST);
            assertThat(execution.executedQueries()).containsExactly("CMN");
            assertThat(execution.selectedChunkCount()).isEqualTo(1);
        });
        var withoutRetrieval = service.executeChat(request);
        assertThat(withoutRetrieval.sources()).isEmpty();
        assertThat(withoutRetrieval.retrievals()).isEmpty();
        assertThatThrownBy(() -> service.executeChat(request)).isInstanceOf(IllegalStateException.class);
        var afterFailure = service.executeChat(request);
        assertThat(afterFailure.sources()).isEmpty();
        assertThat(afterFailure.retrievals()).isEmpty();
        verify(pipeline, times(2)).retrieve(any());
        verify(conversations, times(3)).appendMessage(request.conversationId(), MessageType.ASSISTANT, "answer", null);
    }

    @Test
    void chatJsonExposesHybridDiagnosticsEmptyExecutionsAndWebEvidenceWithoutPrivateMetadata() throws Exception {
        OndaRetrievalPipeline pipeline = mock(OndaRetrievalPipeline.class);
        List<Document> fused;
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var hybrid = new HybridDocumentRetriever(
                    query -> List.of(chunk("a", 0.9), chunk("b", 0.7)),
                    query -> List.of(chunk("a", 8.0), chunk("c", 3.0)), executor, 60);
            fused = hybrid.retrieve(new Query("CMN"));
        }
        when(pipeline.retrieve(new RetrievalRequest("CMN"))).thenReturn(RetrievalResult.fromDocuments(
                fused, List.of("CMN"), RetrievalProfile.BALANCED,
                Map.of(RetrievalStage.TRANSFORM, Duration.ofNanos(125_000),
                        RetrievalStage.RETRIEVE, Duration.ofMillis(12), RetrievalStage.TOTAL, Duration.ofMillis(14))));
        when(pipeline.retrieve(new RetrievalRequest("empty"))).thenReturn(RetrievalResult.fromDocuments(
                List.of(), List.of("empty"), RetrievalProfile.BALANCED, Map.of(RetrievalStage.TOTAL, Duration.ofMillis(2))));
        var callback = FunctionToolCallback.builder("vectorSearchTool", new VectorSearchTool(pipeline))
                .inputType(VectorSearchTool.Request.class).build();

        RestClient.Builder rest = RestClient.builder();
        MockRestServiceServer webServer = MockRestServiceServer.bindTo(rest).build();
        webServer.expect(requestTo("https://api.tavily.com/search")).andRespond(withSuccess("""
                {"results":[{"title":"Public source","url":"https://example.com","content":"Web evidence","score":0.75}]}
                """, MediaType.APPLICATION_JSON));
        WebSearchTool web = new WebSearchTool("test-key", rest);
        ChatModel model = mock(ChatModel.class);
        when(model.getOptions()).thenReturn(ToolCallingChatOptions.builder().build());
        when(model.call(any(Prompt.class))).thenAnswer(invocation -> {
            var options = (ToolCallingChatOptions) ((Prompt) invocation.getArgument(0)).getOptions();
            var context = new ToolContext(options.getToolContext());
            options.getToolCallbacks().getFirst().call("{\"query\":\"CMN\"}", context);
            options.getToolCallbacks().getFirst().call("{\"query\":\"empty\"}", context);
            web.apply(new WebSearchTool.Request("public information"));
            return new ChatResponse(List.of(new Generation(new AssistantMessage("answer"))));
        });
        ChatMessageRepository messages = mock(ChatMessageRepository.class);
        when(messages.findByConversationIdOrderBySequenceNumberAsc(any())).thenReturn(List.of());
        var service = new AgenticRagService(ChatClient.builder(model).defaultTools(callback).build(),
                messages, mock(ConversationService.class), mock(SecurityUserContext.class));
        var mvc = MockMvcBuilders.standaloneSetup(new ChatController(service)).build();

        String json = mvc.perform(post("/api/v1/chat").contentType(MediaType.APPLICATION_JSON).content("""
                        {"conversationId":"%s","message":"question"}
                        """.formatted(UUID.randomUUID())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.answer").value("answer"))
                .andExpect(jsonPath("$.sources.length()").value(4))
                .andExpect(jsonPath("$.sources[0].type").value("DOCUMENT"))
                .andExpect(jsonPath("$.sources[0].relevanceScore").value(2.0 / 61))
                .andExpect(jsonPath("$.sources[0].retrieval.executionId").value(1))
                .andExpect(jsonPath("$.sources[0].retrieval.chunkId").value("a"))
                .andExpect(jsonPath("$.sources[0].retrieval.profile").value("BALANCED"))
                .andExpect(jsonPath("$.sources[0].retrieval.scoreType").value("RRF"))
                .andExpect(jsonPath("$.sources[0].retrieval.rrfK").value(60))
                .andExpect(jsonPath("$.sources[0].retrieval.dense.rank").value(1))
                .andExpect(jsonPath("$.sources[0].retrieval.dense.score").value(0.9))
                .andExpect(jsonPath("$.sources[0].retrieval.lexical.rank").value(1))
                .andExpect(jsonPath("$.sources[0].retrieval.lexical.score").value(8.0))
                .andExpect(jsonPath("$.sources[1].retrieval.dense.rank").value(2))
                .andExpect(jsonPath("$.sources[1].retrieval.lexical").doesNotExist())
                .andExpect(jsonPath("$.sources[2].retrieval.dense").doesNotExist())
                .andExpect(jsonPath("$.sources[2].retrieval.lexical.rank").value(2))
                .andExpect(jsonPath("$.sources[3].type").value("WEB"))
                .andExpect(jsonPath("$.sources[3].url").value("https://example.com"))
                .andExpect(jsonPath("$.sources[3].relevanceScore").value(0.75))
                .andExpect(jsonPath("$.sources[3].retrieval").doesNotExist())
                .andExpect(jsonPath("$.retrievals.length()").value(2))
                .andExpect(jsonPath("$.retrievals[0].id").value(1))
                .andExpect(jsonPath("$.retrievals[0].profile").value("BALANCED"))
                .andExpect(jsonPath("$.retrievals[0].selectedChunkCount").value(3))
                .andExpect(jsonPath("$.retrievals[0].executedQueries[0]").value("CMN"))
                .andExpect(jsonPath("$.retrievals[0].timingsMs.TRANSFORM").value(0.125))
                .andExpect(jsonPath("$.retrievals[0].timingsMs.RETRIEVE").value(12.0))
                .andExpect(jsonPath("$.retrievals[0].timingsMs.TOTAL").value(14.0))
                .andExpect(jsonPath("$.retrievals[1].id").value(2))
                .andExpect(jsonPath("$.retrievals[1].selectedChunkCount").value(0))
                .andExpect(jsonPath("$.retrievals[1].executedQueries[0]").value("empty"))
                .andReturn().getResponse().getContentAsString();
        assertThat(json).doesNotContain("uploaded_by", "private-owner", "internal_secret", "do-not-expose");
        assertThat(WebSearchTool.getLastResults()).isNull();
        webServer.verify();
    }

    private static Document chunk(String id, double score) {
        return Document.builder().id(id).text("evidence " + id).score(score).metadata(Map.of(
                VectorMetadataKeys.DOCUMENT_ID, "doc-" + id, VectorMetadataKeys.SOURCE, id + ".pdf",
                "uploaded_by", "private-owner", "internal_secret", "do-not-expose")).build();
    }
}
