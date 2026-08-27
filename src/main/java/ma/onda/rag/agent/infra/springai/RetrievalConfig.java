package ma.onda.rag.agent.infra.springai;

import io.micrometer.core.instrument.MeterRegistry;
import ma.onda.rag.agent.application.retrieval.OndaRetrievalPipeline;
import ma.onda.rag.agent.infra.tools.VectorSearchProperties;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.PromptTemplate;
import org.springframework.ai.rag.preretrieval.query.transformation.QueryTransformer;
import org.springframework.ai.rag.preretrieval.query.transformation.RewriteQueryTransformer;
import org.springframework.ai.rag.retrieval.join.ConcatenationDocumentJoiner;
import org.springframework.ai.rag.retrieval.search.VectorStoreDocumentRetriever;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(VectorSearchProperties.class)
public class RetrievalConfig {

    @Bean
    OndaRetrievalPipeline ondaRetrievalPipeline(
            VectorStore vectorStore,
            VectorSearchProperties properties,
            OndaDocumentPostProcessor postProcessor,
            MeterRegistry meterRegistry,
            @Qualifier("frenchQueryTransformer") ObjectProvider<QueryTransformer> transformer
    ) {
        return new OndaRetrievalPipeline(
                transformer.stream().toList(), List::of,
                VectorStoreDocumentRetriever.builder().vectorStore(vectorStore)
                        .topK(properties.topK())
                        .similarityThreshold(properties.similarityThreshold())
                        .build(),
                new ConcatenationDocumentJoiner(),
                List.of(postProcessor),
                meterRegistry
        );
    }

    @Bean("frenchQueryTransformer")
    @ConditionalOnProperty(prefix = "rag.pre-retrieval.rewrite", name = "enabled", havingValue = "true")
    QueryTransformer frenchQueryTransformer(ChatModel chatModel) {
        // A dedicated client prevents recursive agent tool use during rewriting.

        PromptTemplate customizedTemplate = new PromptTemplate(
                """
                Rewrite the user query for semantic search in an ONDA document vector store.
                The indexed documents are primarily in French. Preserve the user's language.
                Preserve official ONDA terminology, airport names, acronyms, dates, numbers,
                and named entities exactly. Remove conversational filler.
                Return only the concise rewritten query, without explanation or quotation marks.
                Search system: {target}
                Original query: {query}
                Rewritten query:
                """
        );

        return RewriteQueryTransformer.builder()
                .chatClientBuilder(ChatClient.builder(chatModel)
                        .defaultOptions(ChatOptions.builder().temperature(0.0)))
                .targetSearchSystem("ONDA French document vector store")
                .promptTemplate(customizedTemplate)
                .build();
    }
}
