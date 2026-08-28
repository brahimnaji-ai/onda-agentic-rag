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

    private final Map<RetrievalProfile, RetrievalPlan> plans;
    private final RetrievalProfile defaultProfile;
    private final MeterRegistry meterRegistry;

    public OndaRetrievalPipeline(
             List<QueryTransformer> transformers,
             QueryExpander expander,
             DocumentRetriever retriever,
             DocumentJoiner joiner,
             List<DocumentPostProcessor> postProcessors,
             MeterRegistry meterRegistry
    ) {
        this(Map.of(RetrievalProfile.FAST, new RetrievalPlan(transformers, expander, retriever, joiner, postProcessors)),
                RetrievalProfile.FAST, meterRegistry);
    }

    public OndaRetrievalPipeline(Map<RetrievalProfile, RetrievalPlan> plans, RetrievalProfile defaultProfile,
                                 MeterRegistry meterRegistry) {
        this.plans = Map.copyOf(plans);
        if (!plans.containsKey(defaultProfile)) {
            throw new IllegalArgumentException("The default retrieval profile must have a plan");
        }
        this.defaultProfile = defaultProfile;
        this.meterRegistry = meterRegistry;
    }

    public RetrievalResult retrieve(RetrievalRequest request) {
        RetrievalRequest supplied = request == null ? new RetrievalRequest(null) : request;
        RetrievalProfile profile = supplied.profile() == null ? defaultProfile : supplied.profile();
        RetrievalRequest input = new RetrievalRequest(supplied.query(), profile, supplied.context());
        RetrievalPlan plan = plans.get(profile);
        if (plan == null) {
            throw new IllegalArgumentException("No retrieval plan configured for " + profile);
        }
        Map<RetrievalStage, Duration> timings = new EnumMap<>(RetrievalStage.class);
        Retrieved retrieved = timed(RetrievalStage.TOTAL, input.profile(), timings, () -> execute(input, plan, timings));
        return RetrievalResult.fromDocuments(retrieved.documents(), retrieved.queries(), input.profile(), timings);
    }

    private Retrieved execute(RetrievalRequest request, RetrievalPlan plan, Map<RetrievalStage, Duration> timings) {
        if (request.query() == null || request.query().isBlank()) {
            return new Retrieved(List.of(), List.of());
        }
        Query original = new Query(request.query(), List.of(), request.context());
        Query transformed = timed(RetrievalStage.TRANSFORM, request.profile(), timings, () -> transform(original, plan.transformers()));
        List<Query> queries = timed(RetrievalStage.EXPAND, request.profile(), timings, () -> expand(transformed, plan.expander()));
        Map<Query, List<List<Document>>> candidates = timed(RetrievalStage.RETRIEVE, request.profile(), timings, () -> {
            Map<Query, List<List<Document>>> results = new LinkedHashMap<>();
            for (Query query : queries) {
                List<Document> documents = plan.retriever().retrieve(query);
                results.put(query, List.of(documents == null ? List.of() : documents));
            }
            return results;
        });
        List<Document> joined = timed(RetrievalStage.JOIN, request.profile(), timings, () -> plan.joiner().join(candidates));
        List<Document> selected = timed(RetrievalStage.POST_PROCESS, request.profile(), timings, () -> {
            List<Document> documents = joined;
            for (DocumentPostProcessor processor : plan.postProcessors()) {
                documents = processor.process(original, documents);
            }
            return documents;
        });
        return new Retrieved(selected, queries.stream().map(Query::text).toList());
    }

    private Query transform(Query original, List<QueryTransformer> transformers) {
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

    private List<Query> expand(Query query, QueryExpander expander) {
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
