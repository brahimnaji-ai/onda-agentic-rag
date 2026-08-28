package ma.onda.rag.agent.application.retrieval;

import org.springframework.ai.rag.postretrieval.document.DocumentPostProcessor;
import org.springframework.ai.rag.preretrieval.query.expansion.QueryExpander;
import org.springframework.ai.rag.preretrieval.query.transformation.QueryTransformer;
import org.springframework.ai.rag.retrieval.join.DocumentJoiner;
import org.springframework.ai.rag.retrieval.search.DocumentRetriever;

import java.util.List;
import java.util.Objects;

/** Internal strategy assembly for a profile; the pipeline owns the execution order. */
public record RetrievalPlan(List<QueryTransformer> transformers, QueryExpander expander,
                            DocumentRetriever retriever, DocumentJoiner joiner,
                            List<DocumentPostProcessor> postProcessors) {
    public RetrievalPlan {
        transformers = List.copyOf(transformers);
        Objects.requireNonNull(expander);
        Objects.requireNonNull(retriever);
        Objects.requireNonNull(joiner);
        postProcessors = List.copyOf(postProcessors);
    }
}
