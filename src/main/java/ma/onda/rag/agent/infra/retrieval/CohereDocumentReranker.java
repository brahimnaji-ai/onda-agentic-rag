package ma.onda.rag.agent.infra.retrieval;

import com.fasterxml.jackson.annotation.JsonProperty;
import ma.onda.rag.agent.application.retrieval.DocumentReranker;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;

import java.net.URI;
import java.util.HashMap;
import java.util.List;

/** Cohere v2 bulk adapter; rerank-v3.5 supports multilingual query/document pairs. */
public final class CohereDocumentReranker implements DocumentReranker {
    private final RestClient client;
    private final URI endpoint;
    private final String apiKey;
    private final String model;

    public CohereDocumentReranker(RestClient client, URI endpoint, String apiKey, String model) {
        this.client = client;
        this.endpoint = endpoint;
        this.apiKey = apiKey;
        this.model = model;
    }

    @Override
    public Result rerank(String query, List<Candidate> candidates) {
        if (candidates.isEmpty()) return new Result(java.util.Map.of(), Status.SUCCESS);
        // Request every score, not top-N: evidence selection belongs to the application.
        var response = client.post().uri(endpoint).headers(headers -> headers.setBearerAuth(apiKey))
                .contentType(MediaType.APPLICATION_JSON)
                .body(new Request(model, query, candidates.stream().map(Candidate::text).toList()))
                .retrieve().body(Response.class);
        if (response == null || response.results() == null || response.results().size() != candidates.size()) {
            throw new IllegalStateException("Incomplete reranking response");
        }
        var scores = new HashMap<String, Double>();
        for (Score score : response.results()) {
            if (score == null || score.index() == null || score.index() < 0 || score.index() >= candidates.size()
                    || score.relevanceScore() == null || !Double.isFinite(score.relevanceScore())
                    || scores.putIfAbsent(candidates.get(score.index()).chunkId(), score.relevanceScore()) != null) {
                throw new IllegalStateException("Invalid reranking response");
            }
        }
        return new Result(scores, Status.SUCCESS);
    }

    record Request(String model, String query, List<String> documents) {}
    record Response(List<Score> results) {}
    record Score(Integer index, @JsonProperty("relevance_score") Double relevanceScore) {}
}
