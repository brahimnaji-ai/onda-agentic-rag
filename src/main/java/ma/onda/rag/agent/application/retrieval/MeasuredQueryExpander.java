package ma.onda.rag.agent.application.retrieval;

import org.springframework.ai.rag.Query;
import org.springframework.ai.rag.preretrieval.query.expansion.QueryExpander;
import java.util.List;

/** Expansion diagnostics contain no generated text. Hypothetical text is retrieval-only. */
public interface MeasuredQueryExpander extends QueryExpander {
    String HYPOTHETICAL = "retrieval.hypothetical";
    String HISTORY = "retrieval.user-history";

    Expansion expandMeasured(Query query);

    @Override
    default List<Query> expand(Query query) { return expandMeasured(query).queries(); }

    record Expansion(List<Query> queries, int modelCalls, String status) {
        public Expansion { queries = queries.stream().filter(java.util.Objects::nonNull).toList(); }
    }

    record Diagnostics(int modelCalls, int queryCount, int hypotheticalQueryCount, String status) {
        public static Diagnostics none() { return new Diagnostics(0, 0, 0, "NOT_USED"); }
        public boolean fallback() { return !List.of("SUCCESS", "NOT_USED").contains(status); }
    }
}
