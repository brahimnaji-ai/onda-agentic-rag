package ma.onda.rag.agent.infra.springai;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import ma.onda.rag.agent.application.retrieval.OndaRetrievalPipeline;
import ma.onda.rag.agent.application.retrieval.RetrievalProfile;
import ma.onda.rag.agent.application.retrieval.RetrievalRequest;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.document.Document;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.rag.retrieval.search.VectorStoreDocumentRetriever;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.FilterExpressionTextParser;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class RetrievalConfigTest {

    @Test
    void reviewedPassingReportEnablesOnlyItsSelectedStrategy(@org.junit.jupiter.api.io.TempDir java.nio.file.Path directory)
            throws java.io.IOException {
        var baseline = new DeepApproval.Metrics(60, 60, .8, .6, .98, .98, 2000., 0., 0., 0.);
        var candidate = new DeepApproval.Metrics(60, 60, .8, .7, .98, .98, 3000., 1., 0., 0.);
        var approval = new DeepApproval("onda-v1", "a".repeat(64), "b".repeat(64), true, true, true,
                "test-reviewer", ma.onda.rag.agent.infra.retrieval.DeepQueryExpander.Strategy.MULTI_QUERY,
                baseline, candidate, new DeepApproval.Limits(.05, 5000, 2, 1, 1, .05, .95, .95));
        var report = directory.resolve("approval.json");
        java.nio.file.Files.writeString(report, tools.jackson.databind.json.JsonMapper.builder().build().writeValueAsString(approval));
        ChatModel model = mock(ChatModel.class);
        when(model.getOptions()).thenReturn(ToolCallingChatOptions.builder().build());
        var enabled = contextRunner.withBean(ChatModel.class, () -> model).withPropertyValues(
                "rag.retrieval.profile=DEEP", "rag.retrieval.deep.enabled=true",
                "rag.retrieval.deep.multi-query-enabled=true", "rag.retrieval.deep.approval-report=" + report.toUri());
        enabled.run(context -> {
            assertThat(context).hasNotFailed().hasBean("deepQueryExpander");
            assertThat(context.getBean(OndaRetrievalPipeline.class).retrieve(new RetrievalRequest(null)).profile())
                    .isEqualTo(RetrievalProfile.DEEP);
            verify(model, never()).call(any(Prompt.class));
        });
        enabled.withPropertyValues("rag.retrieval.deep.multi-query-enabled=false", "rag.retrieval.deep.hyde-enabled=true",
                        "rag.retrieval.deep.strategy=HYDE")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void deepIsUnavailableWithoutReviewedApprovalAndFlagsAreMutuallyExclusive() {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed().doesNotHaveBean("deepQueryExpander");
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> context.getBean(OndaRetrievalPipeline.class)
                    .retrieve(new RetrievalRequest("q", RetrievalProfile.DEEP, Map.of())))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("DEEP");
        });
        contextRunner.withPropertyValues("rag.retrieval.deep.enabled=true")
                .run(context -> assertThat(context).hasFailed());
        contextRunner.withPropertyValues("rag.retrieval.deep.multi-query-enabled=true", "rag.retrieval.deep.hyde-enabled=true")
                .run(context -> assertThat(context).hasFailed());
        contextRunner.withPropertyValues("rag.retrieval.profile=DEEP")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void enabledProviderIsResilientAndConfigurationIsValidatedWithoutMakingNetworkCalls() {
        contextRunner.withPropertyValues("rag.post-retrieval.reranking.enabled=true",
                "rag.post-retrieval.reranking.api-key=test-key", "rag.post-retrieval.context-token-budget=1234",
                "rag.post-retrieval.adjacent-chunks-enabled=true").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(ma.onda.rag.agent.application.retrieval.DocumentReranker.class))
                    .isInstanceOf(ma.onda.rag.agent.infra.retrieval.ResilientDocumentReranker.class);
            assertThat(context.getBean(PostRetrievalProperties.class).contextTokenBudget()).isEqualTo(1234);
            assertThat(context.getBean(PostRetrievalProperties.class).adjacentChunksEnabled()).isTrue();
        });
        contextRunner.withPropertyValues("rag.post-retrieval.reranking.timeout=0s")
                .run(context -> assertThat(context).hasFailed());
        contextRunner.withPropertyValues("rag.post-retrieval.reranking.enabled=true")
                .run(context -> assertThat(context).hasFailed());
        contextRunner.withPropertyValues("rag.post-retrieval.context-token-budget=0")
                .run(context -> assertThat(context).hasFailed());
    }

    private final VectorStore vectorStore = mock(VectorStore.class);
    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(RetrievalConfig.class, OndaDocumentPostProcessor.class)
            .withBean(VectorStore.class, () -> vectorStore)
            .withBean(JdbcClient.class, () -> mock(JdbcClient.class))
            .withBean(MeterRegistry.class, SimpleMeterRegistry::new);

    @Test
    void defaultFastWiringNeedsNoChatModelAndHonorsCandidateAndContextLimits() {
        when(vectorStore.similaritySearch(any(SearchRequest.class)))
                .thenReturn(List.of(Document.builder().text("first").score(0.9).build(),
                        Document.builder().text("second").score(0.8).build()));

        contextRunner.withPropertyValues("rag.retrieval.top-k=6", "rag.retrieval.similarity-threshold=0.4",
                "rag.post-retrieval.max-documents=1").run(context -> {
            assertThat(context).hasNotFailed().hasSingleBean(OndaRetrievalPipeline.class)
                    .doesNotHaveBean("frenchQueryTransformer");
            var result = context.getBean(OndaRetrievalPipeline.class).retrieve(new RetrievalRequest("CMN",
                    RetrievalProfile.FAST, Map.of(VectorStoreDocumentRetriever.FILTER_EXPRESSION, "uploaded_by == 'owner'")));
            assertThat(result.documents()).extracting(Document::getText).containsExactly("first");
            assertThat(result.executedQueries()).containsExactly("CMN");
            var captor = org.mockito.ArgumentCaptor.forClass(SearchRequest.class);
            verify(vectorStore).similaritySearch(captor.capture());
            assertThat(captor.getValue().getTopK()).isEqualTo(6);
            assertThat(captor.getValue().getSimilarityThreshold()).isEqualTo(0.4);
            assertThat(captor.getValue().getFilterExpression())
                    .isEqualTo(new FilterExpressionTextParser().parse("uploaded_by == 'owner'"));
        });
    }

    @Test
    void configuredDefaultSelectsBalancedWhileExplicitFastRemainsAvailable() {
        contextRunner.withPropertyValues("rag.retrieval.profile=BALANCED").run(context -> {
            assertThat(context).hasNotFailed();
            var pipeline = context.getBean(OndaRetrievalPipeline.class);
            assertThat(pipeline.retrieve(new RetrievalRequest(null)).profile()).isEqualTo(RetrievalProfile.BALANCED);
            assertThat(pipeline.retrieve(new RetrievalRequest(null, RetrievalProfile.FAST, Map.of())).profile())
                    .isEqualTo(RetrievalProfile.FAST);
        });
    }

    @Test
    void optionalFrenchRewriteUsesDedicatedClientWithoutTools() {
        ChatModel model = mock(ChatModel.class);
        when(model.getOptions()).thenReturn(ToolCallingChatOptions.builder().build());
        when(model.call(any(Prompt.class))).thenReturn(
                new ChatResponse(List.of(new Generation(new AssistantMessage("accès CMN")))));
        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of());

        contextRunner.withBean(ChatModel.class, () -> model)
                .withPropertyValues("rag.pre-retrieval.rewrite.enabled=true").run(context -> {
            assertThat(context).hasNotFailed().hasBean("frenchQueryTransformer");
            var result = context.getBean(OndaRetrievalPipeline.class)
                    .retrieve(new RetrievalRequest("Quelles sont les conditions d'accès à CMN ?"));
            assertThat(result.executedQueries()).containsExactly("Quelles sont les conditions d'accès à CMN ?", "accès CMN");
            var prompt = org.mockito.ArgumentCaptor.forClass(Prompt.class);
            verify(model).call(prompt.capture());
            assertThat(prompt.getValue().getOptions().getTemperature()).isEqualTo(0.0);
            if (prompt.getValue().getOptions() instanceof ToolCallingChatOptions options) {
                assertThat(options.getToolCallbacks()).isNullOrEmpty();
            }
        });
    }
}
