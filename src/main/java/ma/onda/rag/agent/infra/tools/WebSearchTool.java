package ma.onda.rag.agent.infra.tools;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import lombok.extern.slf4j.Slf4j;
import ma.onda.rag.shared.exception.BusinessException;
import ma.onda.rag.shared.exception.ErrorCode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Description;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.Collections;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

@Slf4j
@Component("webSearchTool")
@Description("Search the general live web for current external information not limited to ONDA. Use this for news, regulations, or facts from sources outside the official ONDA website.")
public class WebSearchTool implements Function<WebSearchTool.Request, WebSearchTool.Response> {

    private final String apiKey;
    private final RestClient restClient;

    private static final String TAVILY_API_URL = "https://api.tavily.com/search";
    private static final int MAX_RESULTS = 4;
    private static final int CHUNKS_PER_SOURCE = 3;
    private static final int MAX_EVIDENCE_CHARACTERS = 6_000;
    private static final ThreadLocal<List<WebSearchSnippet>> lastResults = new ThreadLocal<>();

    public WebSearchTool(@Value("${tavily.api-key}") String apiKey, RestClient.Builder restClientBuilder) {
        this.apiKey = apiKey;
        this.restClient = restClientBuilder.build();
    }

    public record Request(
            @JsonPropertyDescription("Search query for external web search")
            String query
    ) {}

    public record Response(
            List<WebSearchSnippet> webResults
    ) {}

    public record WebSearchSnippet(
            String title,
            String url,
            String content,
            Double relevanceScore
    ) {}

    public record TavilyResponse(List<TavilyResult> results) {}
    public record TavilyResult(
            String title,
            String url,
            String content,
            @JsonProperty("raw_content") String rawContent,
            Double score
    ) {}

    /** Returns the general-web evidence retrieved during the current chat request. */
    public static List<WebSearchSnippet> getLastResults() {
        return lastResults.get();
    }

    /** Clears request-local evidence after it has been returned as chat citations. */
    public static void clearLastResults() {
        lastResults.remove();
    }

    @Override
    public Response apply(Request request) {
        if (request == null || request.query() == null || request.query().isBlank()) {
            log.warn("WebSearchTool called with empty or null query");
            return new Response(Collections.emptyList());
        }

        log.info("Executing external web search for query: '{}'", request.query());

        try {
            TavilyResponse tavilyResponse = restClient.post()
                    .uri(TAVILY_API_URL)
                    .body(Map.of(
                            "api_key", apiKey,
                            "query", request.query(),
                            "search_depth", "advanced",
                            "chunks_per_source", CHUNKS_PER_SOURCE,
                            "max_results", MAX_RESULTS,
                            "include_raw_content", true,
                            "include_answer", false
                    ))
                    .retrieve()
                    .body(TavilyResponse.class);

            if (tavilyResponse == null || tavilyResponse.results() == null) {
                return new Response(Collections.emptyList());
            }

            List<WebSearchSnippet> snippets = tavilyResponse.results().stream()
                    .map(result -> new WebSearchSnippet(
                            result.title(),
                            result.url(),
                            selectEvidence(result),
                            result.score()))
                    .filter(result -> !result.content().isBlank())
                    .toList();

            List<WebSearchSnippet> currentResults = lastResults.get();
            if (currentResults == null) {
                currentResults = new ArrayList<>();
            }
            currentResults.addAll(snippets);
            lastResults.set(currentResults);
            
            log.info("WebSearchTool returned {} web snippets", snippets.size());

            return new Response(snippets);
        } catch (RestClientException e) {
            log.error("Failed to execute external web search", e);
            throw new BusinessException(ErrorCode.TOOL_EXECUTION_FAILED, e.getMessage());
        }
    }

    /**
     * Raw page content gives the model evidence beyond a search-result URL or
     * snippet. It is bounded so a single page cannot consume the tool-call
     * context window. Tavily's focused content snippet remains the fallback
     * when a source cannot be extracted.
     */
    private String selectEvidence(TavilyResult result) {
        String evidence = hasText(result.rawContent()) ? result.rawContent() : result.content();
        if (!hasText(evidence)) {
            return "";
        }

        String normalized = evidence.replaceAll("\\s+", " ").trim();
        if (normalized.length() <= MAX_EVIDENCE_CHARACTERS) {
            return normalized;
        }
        return normalized.substring(0, MAX_EVIDENCE_CHARACTERS) + "…";
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
