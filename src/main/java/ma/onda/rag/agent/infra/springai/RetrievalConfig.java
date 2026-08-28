package ma.onda.rag.agent.infra.springai;

import io.micrometer.core.instrument.MeterRegistry;
import ma.onda.rag.agent.application.retrieval.OndaRetrievalPipeline;
import ma.onda.rag.agent.application.retrieval.DocumentReranker;
import ma.onda.rag.agent.infra.retrieval.AdjacentChunkRetriever;
import ma.onda.rag.agent.infra.retrieval.CohereDocumentReranker;
import ma.onda.rag.agent.infra.retrieval.PostgresAdjacentChunkRetriever;
import ma.onda.rag.agent.infra.retrieval.ResilientDocumentReranker;
import ma.onda.rag.agent.application.retrieval.RetrievalPlan;
import ma.onda.rag.agent.application.retrieval.RetrievalProfile;
import ma.onda.rag.agent.infra.retrieval.HybridDocumentRetriever;
import ma.onda.rag.agent.infra.retrieval.PostgresFullTextDocumentRetriever;
import ma.onda.rag.agent.infra.retrieval.RankedDocumentJoiner;
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
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import java.net.http.HttpClient;

import java.util.List;
import java.util.Map;
import java.util.EnumMap;
import ma.onda.rag.agent.infra.retrieval.DeepQueryExpander;
import ma.onda.rag.agent.application.retrieval.MeasuredQueryExpander;
import org.springframework.core.io.ResourceLoader;
import tools.jackson.databind.json.JsonMapper;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({VectorSearchProperties.class, HybridRetrievalProperties.class, RerankingProperties.class, DeepRetrievalProperties.class})
public class RetrievalConfig {

    @Bean(destroyMethod = "close")
    @ConditionalOnProperty(prefix = "rag.retrieval.deep", name = "enabled", havingValue = "true")
    DeepQueryExpander deepQueryExpander(DeepRetrievalProperties properties, ResourceLoader resources, ChatModel model) throws java.io.IOException {
        DeepApproval approval;
        try (var input = resources.getResource(properties.approvalReport()).getInputStream()) {
            approval = JsonMapper.builder().build().readValue(input, DeepApproval.class);
        }
        if (!approval.permits(properties.strategy())) {
            throw new IllegalStateException("DEEP promotion denied: reviewed live quality/latency/cost evidence is required");
        }
        return DeepQueryExpander.create(model, properties.strategy(), properties.expansionTimeout(),
                properties.variants(), properties.maxOutputTokens(), properties.maxConcurrentCalls());
    }

    @Bean
    @ConditionalOnMissingBean(DocumentReranker.class)
    DocumentReranker documentReranker(RerankingProperties properties) {
        if (!properties.enabled()) {
            return (query, candidates) -> DocumentReranker.Result.unavailable(DocumentReranker.Status.DISABLED);
        }
        var http = HttpClient.newBuilder().connectTimeout(properties.timeout()).build();
        var factory = new JdkClientHttpRequestFactory(http);
        factory.setReadTimeout(properties.timeout());
        var provider = new CohereDocumentReranker(RestClient.builder().requestFactory(factory).build(),
                properties.endpoint(), properties.apiKey(), properties.model());
        return new ResilientDocumentReranker(provider, properties.timeout(), properties.maxConcurrentCalls(),
                properties.failureThreshold(), properties.openDuration());
    }

    @Bean
    @ConditionalOnMissingBean(AdjacentChunkRetriever.class)
    AdjacentChunkRetriever adjacentChunkRetriever(JdbcClient jdbc) {
        return new PostgresAdjacentChunkRetriever(jdbc);
    }

    @Bean
    OndaRetrievalPipeline ondaRetrievalPipeline(
            VectorStore vectorStore,
            VectorSearchProperties properties,
            OndaDocumentPostProcessor postProcessor,
            MeterRegistry meterRegistry,
            HybridRetrievalProperties hybridProperties,
            JdbcClient jdbcClient,
            @Qualifier("retrievalExecutor") ExecutorService executor,
            @Qualifier("frenchQueryTransformer") ObjectProvider<QueryTransformer> transformer,
            ObjectProvider<DeepQueryExpander> deepExpander
    ) {
        List<QueryTransformer> transformers = transformer.stream().toList();
        RetrievalPlan fast = new RetrievalPlan(
                transformers, List::of,
                VectorStoreDocumentRetriever.builder().vectorStore(vectorStore)
                        .topK(properties.topK())
                        .similarityThreshold(properties.similarityThreshold())
                        .build(),
                new ConcatenationDocumentJoiner(),
                List.of(postProcessor)
        );
        var dense = VectorStoreDocumentRetriever.builder().vectorStore(vectorStore)
                .topK(hybridProperties.denseCandidates()).similarityThreshold(hybridProperties.similarityThreshold()).build();
        var lexical = new PostgresFullTextDocumentRetriever(jdbcClient, hybridProperties.lexicalCandidates());
        RetrievalPlan balanced = new RetrievalPlan(transformers, List::of,
                new HybridDocumentRetriever(dense, lexical, executor, hybridProperties.rrfK()),
                new RankedDocumentJoiner(), List.of(postProcessor));
        Map<RetrievalProfile, RetrievalPlan> plans = new EnumMap<>(RetrievalProfile.class);
        plans.put(RetrievalProfile.FAST, fast);
        plans.put(RetrievalProfile.BALANCED, balanced);
        deepExpander.ifAvailable(expander -> {
            var syntheticDense = new HybridDocumentRetriever(dense, query -> List.of(), executor, hybridProperties.rrfK());
            plans.put(RetrievalProfile.DEEP, new RetrievalPlan(List.of(), expander,
                    query -> Boolean.TRUE.equals(query.context().get(MeasuredQueryExpander.HYPOTHETICAL))
                            ? syntheticDense.retrieve(query) : balanced.retriever().retrieve(query),
                    new RankedDocumentJoiner(), List.of(postProcessor)));
        });
        return new OndaRetrievalPipeline(plans, properties.profile(), meterRegistry);
    }

    @Bean(name = "retrievalExecutor", destroyMethod = "close")
    ExecutorService retrievalExecutor() {
        return Executors.newVirtualThreadPerTaskExecutor();
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
