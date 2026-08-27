package ma.onda.rag.agent.application.retrieval;

import java.util.Map;

/** Context is application-supplied (for example, a vector-store filter), never tool input. */
public record RetrievalRequest(String query, RetrievalProfile profile, Map<String, Object> context) {

    public RetrievalRequest {
        profile = profile == null ? RetrievalProfile.FAST : profile;
        context = context == null ? Map.of() : Map.copyOf(context);
    }

    public RetrievalRequest(String query) {
        this(query, RetrievalProfile.FAST, Map.of());
    }
}
