package ma.onda.rag.agent.infra.tools;

import ma.onda.rag.shared.exception.BusinessException;
import ma.onda.rag.shared.exception.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class WebSearchToolTest {

    private MockRestServiceServer mockServer;
    private WebSearchTool webSearchTool;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        mockServer = MockRestServiceServer.bindTo(builder).build();
        webSearchTool = new WebSearchTool("test-api-key", builder);
    }

    @Test
    void shouldReturnSnippetsWhenApiCallIsSuccessful() {
        String mockResponse = """
            {
              "results": [
                {
                  "title": "Spring AI RAG",
                  "url": "https://spring.io/ai",
                  "content": "Spring AI supports RAG out of the box."
                }
              ]
            }
            """;

        mockServer.expect(requestTo("https://api.tavily.com/search"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess(mockResponse, MediaType.APPLICATION_JSON));

        WebSearchTool.Response response = webSearchTool.apply(new WebSearchTool.Request("Spring AI RAG"));

        assertThat(response.webResults()).hasSize(1);
        WebSearchTool.WebSearchSnippet snippet = response.webResults().get(0);
        assertThat(snippet.title()).isEqualTo("Spring AI RAG");
        assertThat(snippet.url()).isEqualTo("https://spring.io/ai");
        assertThat(snippet.content()).isEqualTo("Spring AI supports RAG out of the box.");

        mockServer.verify();
    }

    @Test
    void shouldReturnEmptyWhenQueryIsBlank() {
        WebSearchTool.Response response = webSearchTool.apply(new WebSearchTool.Request(""));

        assertThat(response.webResults()).isEmpty();
    }

    @Test
    void shouldThrowBusinessExceptionWhenApiCallFails() {
        mockServer.expect(requestTo("https://api.tavily.com/search"))
                .andRespond(withServerError());

        assertThatThrownBy(() -> webSearchTool.apply(new WebSearchTool.Request("test query")))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.TOOL_EXECUTION_FAILED);

        mockServer.verify();
    }
}
