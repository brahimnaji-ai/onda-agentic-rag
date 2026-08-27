package ma.onda.rag.agent.application.retrieval;

import java.util.ArrayList;
import java.util.List;

/** One instance per chat invocation, explicitly passed through Spring AI's ToolContext. */
public final class RetrievalResults {

    public static final String TOOL_CONTEXT_KEY = RetrievalResults.class.getName();

    private final List<RetrievalResult> results = new ArrayList<>();

    public synchronized void add(RetrievalResult result) {
        results.add(result);
    }

    public synchronized List<RetrievalResult> snapshot() {
        return List.copyOf(results);
    }
}
