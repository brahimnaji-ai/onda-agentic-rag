package ma.onda.rag.agent.application.retrieval;

import java.util.Map;

/** Context is application-supplied, never tool input. A null profile selects the configured default. */
public record RetrievalRequest(String query, RetrievalProfile profile, Map<String, Object> context) {

    public RetrievalRequest {
        context = context == null ? Map.of() : Map.copyOf(context);
    }

    public RetrievalRequest(String query) {
        this(query, null, Map.of());
    }
}
