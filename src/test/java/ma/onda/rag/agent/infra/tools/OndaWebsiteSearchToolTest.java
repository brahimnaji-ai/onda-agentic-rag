package ma.onda.rag.agent.infra.tools;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class OndaWebsiteSearchToolTest {

    private MockRestServiceServer mockServer;
    private OndaWebsiteSearchTool ondaWebsiteSearchTool;

    @AfterEach
    void clearRetrievedResults() {
        OndaWebsiteSearchTool.clearLastResults();
    }

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        mockServer = MockRestServiceServer.bindTo(builder).build();
        ondaWebsiteSearchTool = new OndaWebsiteSearchTool("test-api-key", builder);
    }

    @Test
    void shouldSearchOnlyOndaDomainAndReturnExtractedEvidence() {
        String mockResponse = """
            {
              "results": [
                {
                  "title": "ONDA announcement",
                  "url": "https://www.onda.ma/announcement",
                  "content": "A summary.",
                  "raw_content": "Official ONDA announcement evidence.",
                  "score": 0.97
                }
              ]
            }
            """;

        mockServer.expect(requestTo("https://api.tavily.com/search"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(jsonPath("$.include_domains[0]").value("onda.ma"))
                .andExpect(jsonPath("$.search_depth").value("advanced"))
                .andRespond(withSuccess(mockResponse, MediaType.APPLICATION_JSON));

        OndaWebsiteSearchTool.Response response = ondaWebsiteSearchTool
                .apply(new OndaWebsiteSearchTool.Request("latest ONDA announcement"));

        assertThat(response.webResults()).singleElement().satisfies(result -> {
            assertThat(result.url()).startsWith("https://www.onda.ma/");
            assertThat(result.content()).isEqualTo("Official ONDA announcement evidence.");
            assertThat(result.relevanceScore()).isEqualTo(0.97);
        });
        assertThat(OndaWebsiteSearchTool.getLastResults()).containsExactlyElementsOf(response.webResults());

        mockServer.verify();
    }

    @Test
    void shouldReturnNoResultsForBlankQuery() {
        assertThat(ondaWebsiteSearchTool.apply(new OndaWebsiteSearchTool.Request(" ")).webResults()).isEmpty();
    }
}
