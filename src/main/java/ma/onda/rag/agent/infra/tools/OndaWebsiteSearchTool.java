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

/** Retrieves current information exclusively from ONDA's official website. */

@Slf4j
@Component("ondaWebsiteSearchTool")
@Description("Search only the official ONDA website, onda.ma, for airport, ONDA service, announcement, or policy information. Prefer this tool whenever the user asks about ONDA.")
public class OndaWebsiteSearchTool implements Function<OndaWebsiteSearchTool.Request, OndaWebsiteSearchTool.Response> {

    private static final String TAVILY_API_URL = "https://api.tavily.com/search";
    private static final List<String> ONDA_DOMAINS = List.of("onda.ma");
    private static final int MAX_RESULTS = 4;
    private static final int CHUNKS_PER_SOURCE = 3;
    private static final int MAX_EVIDENCE_CHARACTERS = 6_000;
    private static final ThreadLocal<List<WebSearchResult>> lastResults = new ThreadLocal<>();

    private final String apiKey;
    private final RestClient restClient;

    public OndaWebsiteSearchTool(@Value("${tavily.api-key}") String apiKey, RestClient.Builder restClientBuilder) {
        this.apiKey = apiKey;
        this.restClient = restClientBuilder.build();
    }

    public record Request(
            @JsonPropertyDescription("Search query for information on the official ONDA website")
            String query
    ) {}

    public record Response(List<WebSearchResult> webResults) {}

    public record WebSearchResult(
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

    /** Returns official ONDA evidence retrieved during the current chat request. */
    public static List<WebSearchResult> getLastResults() {
        return lastResults.get();
    }

    /** Clears request-local evidence after it has been returned as chat citations. */
    public static void clearLastResults() {
        lastResults.remove();
    }

    @Override
    public Response apply(Request request) {
        if (request == null || request.query() == null || request.query().isBlank()) {
            log.warn("OndaWebsiteSearchTool called with empty or null query");
            return new Response(Collections.emptyList());
        }

        try {
            TavilyResponse tavilyResponse = restClient.post()
                    .uri(TAVILY_API_URL)
                    .body(Map.of(
                            "api_key", apiKey,
                            "query", request.query(),
                            "search_depth", "advanced",
                            "chunks_per_source", CHUNKS_PER_SOURCE,
                            "max_results", MAX_RESULTS,
                            "include_domains", ONDA_DOMAINS,
                            "include_raw_content", true,
                            "include_answer", false
                    ))
                    .retrieve()
                    .body(TavilyResponse.class);

            if (tavilyResponse == null || tavilyResponse.results() == null) {
                return new Response(Collections.emptyList());
            }

            List<WebSearchResult> results = tavilyResponse.results().stream()
                    .map(result -> new WebSearchResult(
                            result.title(), result.url(), selectEvidence(result), result.score()))
                    .filter(result -> !result.content().isBlank())
                    .toList();

            List<WebSearchResult> currentResults = lastResults.get();
            if (currentResults == null) {
                currentResults = new ArrayList<>();
            }
            currentResults.addAll(results);
            lastResults.set(currentResults);

            log.info("OndaWebsiteSearchTool returned {} ONDA web results", results.size());
            return new Response(results);
        } catch (RestClientException e) {
            log.error("Failed to search the official ONDA website", e);
            throw new BusinessException(ErrorCode.TOOL_EXECUTION_FAILED, e.getMessage());
        }
    }

    private String selectEvidence(TavilyResult result) {
        String evidence = hasText(result.rawContent()) ? result.rawContent() : result.content();
        if (!hasText(evidence)) {
            return "";
        }

        String normalized = evidence.replaceAll("\\s+", " ").trim();
        return normalized.length() <= MAX_EVIDENCE_CHARACTERS
                ? normalized
                : normalized.substring(0, MAX_EVIDENCE_CHARACTERS) + "…";
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
