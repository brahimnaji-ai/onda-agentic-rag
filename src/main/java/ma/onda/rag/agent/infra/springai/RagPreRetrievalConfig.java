package ma.onda.rag.agent.infra.springai;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.prompt.PromptTemplate;
import org.springframework.ai.rag.preretrieval.query.transformation.QueryTransformer;
import org.springframework.ai.rag.preretrieval.query.transformation.RewriteQueryTransformer;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
class RagPreRetrievalConfig {

    @Bean("frenchQueryTransformer")
    @ConditionalOnProperty(
            prefix = "rag.pre-retrieval.rewrite",
            name = "enabled",
            havingValue = "true",
            matchIfMissing = true
    )
    QueryTransformer frenchQueryTransformer(ChatClient.Builder chatClientBuilder) {
        PromptTemplate frenchPreservingPrompt = new PromptTemplate("""
                Rewrite the user query for semantic search in an ONDA document vector store.
                The indexed documents are primarily in French.

                Preserve the user's language. When the query is in French, return French only.
                Preserve official ONDA terminology, airport names, acronyms, dates, numbers, and named entities exactly.
                Remove conversational filler and make the query concise and specific.
                Return only the rewritten query, without an explanation or quotation marks.

                Search system: {target}
                Original query: {query}
                Rewritten query:
                """);

        return RewriteQueryTransformer.builder()
                .chatClientBuilder(chatClientBuilder)
                .promptTemplate(frenchPreservingPrompt)
                .targetSearchSystem("ONDA French document vector store")
                .build();
    }
}
