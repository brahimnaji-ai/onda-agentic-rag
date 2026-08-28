package ma.onda.rag.agent.application.retrieval;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** One instance per chat invocation, explicitly passed through Spring AI's ToolContext. */
public final class RetrievalResults {

    public static final String TOOL_CONTEXT_KEY = RetrievalResults.class.getName();

    private final List<RetrievalResult> results = new ArrayList<>();
    private long usedTokens;
    private long usedDocuments;
    private final List<String> userHistory;

    public RetrievalResults() { this(List.of()); }

    public RetrievalResults(List<String> userHistory) { this.userHistory = List.copyOf(userHistory); }

    public synchronized void add(RetrievalResult result) {
        results.add(result);
        boolean budgeted = false;
        for (var document : result.documents()) {
            if (document.getMetadata().get("retrieval") instanceof Map<?, ?> diagnostics
                    && diagnostics.get("context_tokens") instanceof Number tokens) {
                usedTokens += tokens.longValue();
                usedDocuments++;
                budgeted = true;
            }
        }
        if (budgeted) usedTokens += EvidenceBudget.ENVELOPE_TOKENS;
    }

    public synchronized Map<String, Object> budgetContext() {
        var context = new java.util.LinkedHashMap<String, Object>();
        if (usedDocuments > 0) {
            context.put(EvidenceBudget.USED_TOKENS, usedTokens);
            context.put(EvidenceBudget.USED_DOCUMENTS, usedDocuments);
        }
        if (!userHistory.isEmpty()) context.put(MeasuredQueryExpander.HISTORY, userHistory);
        return Map.copyOf(context);
    }

    public synchronized List<RetrievalResult> snapshot() {
        return List.copyOf(results);
    }
}
