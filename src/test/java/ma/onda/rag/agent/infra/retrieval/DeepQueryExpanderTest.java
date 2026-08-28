package ma.onda.rag.agent.infra.retrieval;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import ma.onda.rag.agent.application.retrieval.*;
import ma.onda.rag.agent.infra.springai.OndaDocumentPostProcessor;
import ma.onda.rag.agent.infra.springai.PostRetrievalProperties;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.document.Document;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.rag.Query;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class DeepQueryExpanderTest {
    @Test
    void springMultiQueryMakesOneCallRetainsOriginalAndUsesHistoryWithNoAgentTools() {
        ChatModel model = model("RBA bus tarif\nRabat-Salé bus carte bancaire");
        try (var expander = DeepQueryExpander.create(model, DeepQueryExpander.Strategy.MULTI_QUERY,
                Duration.ofSeconds(2), 2, 384, 2)) {
            var result = expander.expandMeasured(new Query("Et le paiement ?", List.of(),
                    Map.of(MeasuredQueryExpander.HISTORY, List.of("Bus de RBA"), "filter", "owner")));
            assertThat(result.queries()).extracting(Query::text).containsExactly("Et le paiement ?", "RBA bus tarif", "Rabat-Salé bus carte bancaire");
            assertThat(result.queries()).allSatisfy(q -> assertThat(q.context()).containsEntry("filter", "owner"));
            assertThat(result.modelCalls()).isEqualTo(1);
            var prompt = org.mockito.ArgumentCaptor.forClass(Prompt.class);
            verify(model).call(prompt.capture());
            assertThat(prompt.getValue().getContents()).contains("Bus de RBA", "Et le paiement ?");
            if (prompt.getValue().getOptions() instanceof ToolCallingChatOptions options) {
                assertThat(options.getToolCallbacks()).isNullOrEmpty();
            }
        }
    }

    @Test
    void hydeTextIsOnlyARetrievalQueryAndNeverReturnedAsEvidenceOrExecutedQueryText() {
        String hypothetical = "HYPOTHETICAL invented policy details";
        try (var expander = DeepQueryExpander.create(model(hypothetical), DeepQueryExpander.Strategy.HYDE,
                Duration.ofSeconds(2), 2, 384, 2)) {
            var observed = new java.util.ArrayList<Query>();
            var plan = new RetrievalPlan(List.of(), expander, q -> {
                observed.add(q);
                return List.of(Document.builder().id("real").text("Real source excerpt").score(.03)
                        .metadata(Map.of("document_id", "doc", "source", "source.pdf")).build());
            }, new RankedDocumentJoiner(), List.of(new OndaDocumentPostProcessor(new PostRetrievalProperties(4))));
            var pipeline = new OndaRetrievalPipeline(Map.of(RetrievalProfile.DEEP, plan), RetrievalProfile.DEEP, new SimpleMeterRegistry());
            var result = pipeline.retrieve(new RetrievalRequest("original"));
            assertThat(observed).extracting(Query::text).containsExactly("original", hypothetical);
            assertThat(observed.getLast().context()).containsEntry(MeasuredQueryExpander.HYPOTHETICAL, true);
            assertThat(result.executedQueries()).containsExactly("original");
            assertThat(result.documents()).extracting(Document::getText).containsExactly("Real source excerpt");
            assertThat(result.sources()).extracting(RetrievalResult.Source::sourceFilename).containsExactly("source.pdf");
            assertThat(result.candidateChunkIds()).containsExactly("real");
            assertThat(result.expansion().hypotheticalQueryCount()).isEqualTo(1);
        }
    }

    @Test
    void failureAndMalformedMultiQueryOutputRetainOriginal() {
        for (String text : List.of("", "only one variant")) {
            try (var expander = DeepQueryExpander.create(model(text), DeepQueryExpander.Strategy.MULTI_QUERY,
                    Duration.ofSeconds(2), 2, 384, 2)) {
                var result = expander.expandMeasured(new Query("original"));
                assertThat(result.queries()).extracting(Query::text).containsExactly("original");
                assertThat(result.status()).isEqualTo("EMPTY");
            }
        }
        try (var expander = new DeepQueryExpander(q -> { throw new IllegalStateException("offline"); },
                DeepQueryExpander.Strategy.HYDE, Duration.ofSeconds(1), 1, 2)) {
            assertThat(expander.expandMeasured(new Query("q")).status()).isEqualTo("ERROR");
        }
    }

    @Test
    void timeoutIsBoundedAndBusyProviderKeepsItsBulkheadSlot() {
        CountDownLatch release = new CountDownLatch(1);
        try (var expander = new DeepQueryExpander(q -> {
            while (release.getCount() > 0) {
                try { release.await(1, TimeUnit.SECONDS); } catch (InterruptedException ignored) { }
            }
            return List.of(new Query("late hypothetical"));
        }, DeepQueryExpander.Strategy.HYDE, Duration.ofMillis(100), 1, 1)) {
            var result = expander.expandMeasured(new Query("original"));
            assertThat(result.status()).isEqualTo("TIMEOUT");
            assertThat(result.queries()).extracting(Query::text).containsExactly("original");
            assertThat(expander.expandMeasured(new Query("next")).status()).isEqualTo("BULKHEAD_FULL");
        } finally { release.countDown(); }
    }

    private static ChatModel model(String text) {
        ChatModel model = mock(ChatModel.class);
        when(model.getOptions()).thenReturn(ToolCallingChatOptions.builder().build());
        when(model.call(any(Prompt.class))).thenReturn(new ChatResponse(List.of(new Generation(new AssistantMessage(text)))));
        return model;
    }
}
