package ma.onda.rag.agent.infra.retrieval;

import ma.onda.rag.agent.application.retrieval.DocumentReranker;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.net.URI;
import java.util.List;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

class CohereDocumentRerankerTest {
    private final RestClient.Builder builder = RestClient.builder();
    private final MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
    private final CohereDocumentReranker adapter = new CohereDocumentReranker(builder.build(),
            URI.create("https://reranker.example/v2/rerank"), "test-key", "rerank-v3.5");
    private final List<DocumentReranker.Candidate> candidates = List.of(
            new DocumentReranker.Candidate("fr", "Conditions d'accès à l'aéroport"),
            new DocumentReranker.Candidate("ar", "مطار محمد الخامس"));

    @Test
    void sendsOneMultilingualBatchAndMapsUnorderedResponseIndicesToStableIds() {
        server.expect(requestTo("https://reranker.example/v2/rerank")).andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", "Bearer test-key"))
                .andExpect(content().json("""
                        {"model":"rerank-v3.5","query":"الوصول إلى CMN",
                         "documents":["Conditions d'accès à l'aéroport","مطار محمد الخامس"]}
                        """))
                .andRespond(withSuccess("""
                        {"results":[{"index":1,"relevance_score":0.95},{"index":0,"relevance_score":0.3}]}
                        """, MediaType.APPLICATION_JSON));
        var result = adapter.rerank("الوصول إلى CMN", candidates);
        assertThat(result.status()).isEqualTo(DocumentReranker.Status.SUCCESS);
        assertThat(result.scores()).containsExactlyInAnyOrderEntriesOf(java.util.Map.of("fr", 0.3, "ar", 0.95));
        server.verify();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{}", "{\"results\":[]}",
            "{\"results\":[{\"index\":0,\"relevance_score\":0.5}]}",
            "{\"results\":[{\"index\":0,\"relevance_score\":0.5},{\"index\":0,\"relevance_score\":0.6}]}",
            "{\"results\":[{\"index\":0,\"relevance_score\":0.5},{\"index\":8,\"relevance_score\":0.6}]}",
            "{\"results\":[{\"index\":0,\"relevance_score\":0.5},{\"index\":1}]}",
            "{\"results\":[{\"index\":0,\"relevance_score\":0.5},{\"relevance_score\":0.6}]}"
    })
    void rejectsMalformedOrPartialBatchesInsteadOfMisassigningScores(String response) {
        server.expect(requestTo("https://reranker.example/v2/rerank"))
                .andRespond(withSuccess(response, MediaType.APPLICATION_JSON));
        assertThatThrownBy(() -> adapter.rerank("q", candidates)).isInstanceOf(IllegalStateException.class);
        server.verify();
    }

    @Test
    void emptyBatchDoesNotCallHttpAndServerErrorsAreNotSwallowedByTheProvider() {
        assertThat(adapter.rerank("q", List.of()).scores()).isEmpty();
        server.expect(requestTo("https://reranker.example/v2/rerank")).andRespond(withServerError());
        assertThatThrownBy(() -> adapter.rerank("q", candidates)).isInstanceOf(org.springframework.web.client.RestClientException.class);
        server.verify();
    }
}
