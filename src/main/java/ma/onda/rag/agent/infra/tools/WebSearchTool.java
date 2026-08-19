package ma.onda.rag.agent.infra.tools;

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
import java.util.List;
import java.util.Map;
import java.util.function.Function;

@Slf4j
@Component("webSearchTool")
@Description("Search the live web for external real-time information")
public class WebSearchTool implements Function<WebSearchTool.Request, WebSearchTool.Response> {

    private final String apiKey;
    private final RestClient restClient;
    
    private static final String TAVILY_API_URL = "https://api.tavily.com/search";

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
            String content
    ) {}

    public record TavilyResponse(List<TavilyResult> results) {}
    public record TavilyResult(String title, String url, String content) {}

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
                            "search_depth", "basic",
                            "include_answer", false
                    ))
                    .retrieve()
                    .body(TavilyResponse.class);

            if (tavilyResponse == null || tavilyResponse.results() == null) {
                return new Response(Collections.emptyList());
            }

            List<WebSearchSnippet> snippets = tavilyResponse.results().stream()
                    .map(result -> new WebSearchSnippet(result.title(), result.url(), result.content()))
                    .toList();
            
            log.info("WebSearchTool returned {} web snippets", snippets.size());

            return new Response(snippets);
        } catch (RestClientException e) {
            log.error("Failed to execute external web search", e);
            throw new BusinessException(ErrorCode.TOOL_EXECUTION_FAILED, e.getMessage());
        }
    }
}
