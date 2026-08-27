package ma.onda.rag.agent.infra.tools;

import ma.onda.rag.agent.application.retrieval.OndaRetrievalPipeline;
import ma.onda.rag.agent.application.retrieval.RetrievalProfile;
import ma.onda.rag.agent.application.retrieval.RetrievalRequest;
import ma.onda.rag.agent.application.retrieval.RetrievalResult;
import ma.onda.rag.agent.application.retrieval.RetrievalResults;
import ma.onda.rag.document.infra.VectorMetadataKeys;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.document.Document;
import org.springframework.ai.tool.function.FunctionToolCallback;

import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class VectorSearchToolTest {

    private final OndaRetrievalPipeline pipeline = mock(OndaRetrievalPipeline.class);
    private final VectorSearchTool tool = new VectorSearchTool(pipeline);

    @Test
    void mapsExplicitEvidenceToTheExistingSnippetShape() {
        Document document = Document.builder().id("chunk-1").text("Conditions d'accès")
                .metadata(Map.of(VectorMetadataKeys.DOCUMENT_ID, "doc-1", VectorMetadataKeys.SOURCE, "guide.pdf"))
                .score(0.88).build();
        RetrievalResult result = RetrievalResult.fromDocuments(List.of(document), List.of("accès"),
                RetrievalProfile.FAST, Map.of());
        when(pipeline.retrieve(new RetrievalRequest("accès"))).thenReturn(result);

        VectorSearchTool.Response response = tool.apply(new VectorSearchTool.Request("accès"));

        assertThat(response.snippets()).containsExactly(new VectorSearchTool.DocumentSnippet(
                "doc-1", "guide.pdf", "Conditions d'accès", 0.88));
        verify(pipeline).retrieve(new RetrievalRequest("accès"));
    }

    @Test
    void leavesInputValidationToThePipeline() {
        when(pipeline.retrieve(new RetrievalRequest(null))).thenReturn(
                RetrievalResult.fromDocuments(List.of(), List.of(), RetrievalProfile.FAST, Map.of()));
        assertThat(tool.apply(null).snippets()).isEmpty();
        assertThat(tool.apply(new VectorSearchTool.Request(null)).snippets()).isEmpty();
        verify(pipeline, times(2)).retrieve(new RetrievalRequest(null));
    }

    @Test
    void propagatesRepeatedCallbackResultsAcrossThreadsWithoutMixingRequests() throws Exception {
        RetrievalResult first = RetrievalResult.fromDocuments(List.of(new Document("first evidence")),
                List.of("first"), RetrievalProfile.FAST, Map.of());
        RetrievalResult second = RetrievalResult.fromDocuments(List.of(new Document("second evidence")),
                List.of("second"), RetrievalProfile.FAST, Map.of());
        when(pipeline.retrieve(new RetrievalRequest("first"))).thenReturn(first);
        when(pipeline.retrieve(new RetrievalRequest("second"))).thenReturn(second);
        RetrievalResults firstChat = new RetrievalResults();
        RetrievalResults secondChat = new RetrievalResults();
        var callback = FunctionToolCallback.builder("vectorSearchTool", tool)
                .inputType(VectorSearchTool.Request.class).build();
        ToolContext firstContext = new ToolContext(Map.of(RetrievalResults.TOOL_CONTEXT_KEY, firstChat));
        ToolContext secondContext = new ToolContext(Map.of(RetrievalResults.TOOL_CONTEXT_KEY, secondChat));

        try (var executor = Executors.newFixedThreadPool(2)) {
            var firstCall = executor.submit(() -> callback.call("{\"query\":\"first\"}", firstContext));
            var secondCall = executor.submit(() -> callback.call("{\"query\":\"second\"}", secondContext));
            assertThat(firstCall.get()).contains("snippets", "first evidence").doesNotContain("executedQueries", "timings");
            assertThat(secondCall.get()).contains("second evidence").doesNotContain("first evidence");
            executor.submit(() -> callback.call("{\"query\":\"first\"}", firstContext)).get();
        }

        assertThat(firstChat.snapshot()).containsExactly(first, first);
        assertThat(secondChat.snapshot()).containsExactly(second);
        assertThat(callback.getToolDefinition().inputSchema()).contains("query").doesNotContain("TOOL_CONTEXT_KEY", "profile");
        assertThat(tool.apply(new VectorSearchTool.Request("first")).snippets()).hasSize(1);
        assertThat(firstChat.snapshot()).hasSize(2);
    }
}
