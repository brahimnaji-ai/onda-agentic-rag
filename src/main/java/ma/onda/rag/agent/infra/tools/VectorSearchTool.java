package ma.onda.rag.agent.infra.tools;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import lombok.RequiredArgsConstructor;
import ma.onda.rag.agent.application.retrieval.OndaRetrievalPipeline;
import ma.onda.rag.agent.application.retrieval.RetrievalRequest;
import ma.onda.rag.agent.application.retrieval.RetrievalResult;
import ma.onda.rag.agent.application.retrieval.RetrievalResults;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.context.annotation.Description;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.function.BiFunction;

@Component("vectorSearchTool")
@Description("Search PostgreSQL pgvector vector store for relevant document snippets")
@RequiredArgsConstructor
public class VectorSearchTool implements BiFunction<VectorSearchTool.Request, ToolContext, VectorSearchTool.Response> {

    private final OndaRetrievalPipeline pipeline;

    public record Request(@JsonPropertyDescription("The natural language search query") String query) {}

    public record Response(List<DocumentSnippet> snippets) {}

    public record DocumentSnippet(String documentId, String sourceFilename, String chunkContent, Double relevanceScore) {}

    public Response apply(Request request) {
        return apply(request, null);
    }

    @Override
    public Response apply(Request request, ToolContext context) {
        if (context != null && context.getContext().get(RetrievalResults.TOOL_CONTEXT_KEY) instanceof RetrievalResults results) {
            // Atomic selection/accounting for parallel or repeated tool calls in one chat.
            synchronized (results) {
                RetrievalResult result = pipeline.retrieve(new RetrievalRequest(
                        request == null ? null : request.query(), null, results.budgetContext()));
                results.add(result);
                return response(result);
            }
        }
        return response(pipeline.retrieve(new RetrievalRequest(request == null ? null : request.query())));
    }

    private Response response(RetrievalResult result) {
        return new Response(result.sources().stream().map(source -> new DocumentSnippet(
                source.documentId(), source.sourceFilename(), source.chunkContent(), source.relevanceScore())).toList());
    }
}
