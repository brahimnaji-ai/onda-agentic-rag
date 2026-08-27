package ma.onda.rag.agent.application.retrieval;

import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.rag.Query;
import org.springframework.ai.rag.postretrieval.document.DocumentPostProcessor;
import org.springframework.ai.rag.preretrieval.query.expansion.QueryExpander;
import org.springframework.ai.rag.preretrieval.query.transformation.QueryTransformer;
import org.springframework.ai.rag.retrieval.join.DocumentJoiner;
import org.springframework.ai.rag.retrieval.search.DocumentRetriever;

import java.time.Duration;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/** Owns stage ordering; the agent remains responsible for deciding whether to retrieve. */
@Slf4j
public class OndaRetrievalPipeline {

    private final List<QueryTransformer> transformers;
    private final QueryExpander expander;
    private final DocumentRetriever retriever;
    private final DocumentJoiner joiner;
    private final List<DocumentPostProcessor> postProcessors;
    private final MeterRegistry meterRegistry;

    public OndaRetrievalPipeline(
             List<QueryTransformer> transformers,
             QueryExpander expander,
             DocumentRetriever retriever,
             DocumentJoiner joiner,
             List<DocumentPostProcessor> postProcessors,
             MeterRegistry meterRegistry
    ) {
        this.transformers = List.copyOf(transformers);
        this.expander = expander;
        this.retriever = retriever;
        this.joiner = joiner;
        this.postProcessors = List.copyOf(postProcessors);
        this.meterRegistry = meterRegistry;
    }

    public RetrievalResult retrieve(RetrievalRequest request) {
        RetrievalRequest input = request == null ? new RetrievalRequest(null) : request;
        Map<RetrievalStage, Duration> timings = new EnumMap<>(RetrievalStage.class);
        Retrieved retrieved = timed(RetrievalStage.TOTAL, input.profile(), timings, () -> execute(input, timings));
        return RetrievalResult.fromDocuments(retrieved.documents(), retrieved.queries(), input.profile(), timings);
    }

    private Retrieved execute(RetrievalRequest request, Map<RetrievalStage, Duration> timings) {
        if (request.query() == null || request.query().isBlank()) {
            return new Retrieved(List.of(), List.of());
        }
        Query original = new Query(request.query(), List.of(), request.context());
        Query transformed = timed(RetrievalStage.TRANSFORM, request.profile(), timings, () -> transform(original));
        List<Query> queries = timed(RetrievalStage.EXPAND, request.profile(), timings, () -> expand(transformed));
        Map<Query, List<List<Document>>> candidates = timed(RetrievalStage.RETRIEVE, request.profile(), timings, () -> {
            Map<Query, List<List<Document>>> results = new LinkedHashMap<>();
            for (Query query : queries) {
                List<Document> documents = retriever.retrieve(query);
                results.put(query, List.of(documents == null ? List.of() : documents));
            }
            return results;
        });
        List<Document> joined = timed(RetrievalStage.JOIN, request.profile(), timings, () -> joiner.join(candidates));
        List<Document> selected = timed(RetrievalStage.POST_PROCESS, request.profile(), timings, () -> {
            List<Document> documents = joined;
            for (DocumentPostProcessor processor : postProcessors) {
                documents = processor.process(original, documents);
            }
            return documents;
        });
        return new Retrieved(selected, queries.stream().map(Query::text).toList());
    }

    private Query transform(Query original) {
        Query current = original;
        for (QueryTransformer transformer : transformers) {
            Query transformed = transformer.transform(current);
            if (transformed == null) {
                log.warn("Retrieval transformation returned no query; retaining previous query");
            } else {
                // Preserve application-supplied context, including filters, across strategies.
                current = current.mutate().text(transformed.text()).build();
            }
        }
        return current;
    }

    private List<Query> expand(Query query) {
        List<Query> expanded = expander.expand(query);
        if (expanded == null || expanded.isEmpty()) {
            return List.of(query);
        }
        List<Query> queries = expanded.stream().filter(candidate -> candidate != null)
                .map(candidate -> query.mutate().text(candidate.text()).build()).distinct().toList();
        return queries.isEmpty() ? List.of(query) : queries;
    }

    private <T> T timed(
            RetrievalStage stage,
            RetrievalProfile profile,
            Map<RetrievalStage, Duration> timings,
            Supplier<T> operation
    ) {
        long start = System.nanoTime();
        String outcome = "success";
        try {
            return operation.get();
        } catch (RuntimeException | Error failure) {
            outcome = "error";
            throw failure;
        } finally {
            Duration duration = Duration.ofNanos(System.nanoTime() - start);
            timings.put(stage, duration);
            meterRegistry.timer("rag.retrieval.stage", "profile", profile.name(),
                    "stage", stage.name(), "outcome", outcome).record(duration);
            // Never include queries, document/user identifiers, or exception messages here.
            log.debug("Retrieval stage profile={} stage={} outcome={} durationMs={}",
                    profile, stage, outcome, duration.toMillis());
        }
    }

    private record Retrieved(List<Document> documents, List<String> queries) {}
}
