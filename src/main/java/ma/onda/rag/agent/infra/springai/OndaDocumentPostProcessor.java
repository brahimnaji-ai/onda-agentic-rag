package ma.onda.rag.agent.infra.springai;

import lombok.extern.slf4j.Slf4j;
import ma.onda.rag.agent.application.retrieval.DocumentReranker;
import ma.onda.rag.agent.application.retrieval.EvidenceBudget;
import ma.onda.rag.agent.infra.retrieval.AdjacentChunkRetriever;
import ma.onda.rag.agent.infra.retrieval.HybridDocumentRetriever;
import org.springframework.ai.document.Document;
import org.springframework.ai.rag.Query;
import org.springframework.ai.rag.postretrieval.document.DocumentPostProcessor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Stable-ID dedupe -> reranking -> budget selection -> optional budgeted neighbors. */
@Slf4j
@Component("ondaDocumentPostProcessor")
@EnableConfigurationProperties(PostRetrievalProperties.class)
public class OndaDocumentPostProcessor implements DocumentPostProcessor {
    private final PostRetrievalProperties properties;
    private final DocumentReranker reranker;
    private final AdjacentChunkRetriever neighbors;

    public OndaDocumentPostProcessor(PostRetrievalProperties properties) {
        this(properties, (query, candidates) -> DocumentReranker.Result.unavailable(DocumentReranker.Status.DISABLED),
                (query, anchor) -> List.of());
    }

    @Autowired
    public OndaDocumentPostProcessor(PostRetrievalProperties properties, DocumentReranker reranker,
                                    AdjacentChunkRetriever neighbors) {
        this.properties = properties;
        this.reranker = reranker;
        this.neighbors = neighbors;
    }

    @Override
    public List<Document> process(Query query, List<Document> documents) {
        if (documents == null || documents.isEmpty()) return List.of();
        int tokens = remaining(properties.contextTokenBudget(), query, EvidenceBudget.USED_TOKENS)
                - EvidenceBudget.ENVELOPE_TOKENS;
        int count = remaining(properties.maxDocuments(), query, EvidenceBudget.USED_DOCUMENTS);
        if (tokens <= 0 || count <= 0) return List.of();

        Map<String, Document> unique = new LinkedHashMap<>();
        documents.stream().filter(this::usable)
                .sorted(Comparator.comparingDouble(OndaDocumentPostProcessor::recallScore).reversed()
                        .thenComparing(Document::getId))
                .forEach(document -> unique.putIfAbsent(document.getId(), document));
        List<Document> candidates = List.copyOf(unique.values());
        if (candidates.isEmpty()) return List.of();
        DocumentReranker.Result ranking = rerank(query, candidates);
        List<Document> ordered = candidates;
        if (ranking.status() == DocumentReranker.Status.SUCCESS) {
            // Stable sort preserves RRF order for equal reranker scores.
            ordered = candidates.stream().sorted(Comparator.comparingDouble(
                    (Document document) -> ranking.scores().get(document.getId())).reversed()).toList();
        }
        List<Document> selected = new ArrayList<>();
        for (Document document : ordered) {
            if (selected.size() >= count) break;
            int cost = EvidenceBudget.tokens(document);
            if (selected.size() < count && cost <= tokens) {
                selected.add(annotate(document, ranking, cost, false));
                tokens -= cost;
            }
        }
        if (properties.adjacentChunksEnabled() && selected.size() < count && tokens > 0) {
            var ids = new java.util.HashSet<>(selected.stream().map(Document::getId).toList());
            for (Document anchor : List.copyOf(selected)) {
                if (selected.size() >= count || tokens <= 0) break;
                List<Document> adjacent;
                try {
                    adjacent = neighbors.retrieve(query, anchor);
                } catch (RuntimeException failure) {
                    log.debug("Adjacent chunk retrieval unavailable; retaining selected evidence");
                    continue;
                }
                if (adjacent == null) continue;
                for (Document neighbor : adjacent) {
                    if (!usable(neighbor) || ids.contains(neighbor.getId())) continue;
                    int cost = EvidenceBudget.tokens(neighbor);
                    if (selected.size() < count && cost <= tokens) {
                        selected.add(annotate(neighbor, ranking, cost, true));
                        ids.add(neighbor.getId());
                        tokens -= cost;
                    }
                }
            }
        }
        return List.copyOf(selected);
    }

    private DocumentReranker.Result rerank(Query query, List<Document> documents) {
        try {
            var result = reranker.rerank(query.text(), documents.stream()
                    .map(document -> new DocumentReranker.Candidate(document.getId(), document.getText())).toList());
            if (result == null || (result.status() == DocumentReranker.Status.SUCCESS
                    && (result.scores().size() != documents.size()
                    || documents.stream().anyMatch(document -> !result.scores().containsKey(document.getId()))
                    || result.scores().values().stream().anyMatch(score -> !Double.isFinite(score))))) {
                return DocumentReranker.Result.unavailable(DocumentReranker.Status.ERROR);
            }
            return result;
        } catch (RuntimeException failure) {
            return DocumentReranker.Result.unavailable(DocumentReranker.Status.ERROR);
        }
    }

    private boolean usable(Document document) {
        return document != null && StringUtils.hasText(document.getId()) && StringUtils.hasText(document.getText());
    }

    private static int remaining(int limit, Query query, String key) {
        Object used = query.context().get(key);
        long consumed = used instanceof Number number ? Math.max(0, number.longValue()) : 0;
        return (int) Math.max(0, limit - consumed);
    }

    private static double recallScore(Document document) {
        Object rrf = diagnostics(document).get("rrf_score");
        Double score = rrf instanceof Number number ? number.doubleValue() : document.getScore();
        return score == null || !Double.isFinite(score) ? 0 : score;
    }

    private static Map<String, Object> diagnostics(Document document) {
        Map<String, Object> result = new LinkedHashMap<>();
        if (document.getMetadata().get(HybridDocumentRetriever.DIAGNOSTICS_KEY) instanceof Map<?, ?> values) {
            values.forEach((key, value) -> result.put(key.toString(), value));
        }
        return result;
    }

    private static Document annotate(Document document, DocumentReranker.Result ranking, int tokens, boolean adjacent) {
        Map<String, Object> diagnostics = diagnostics(document);
        diagnostics.remove("reranker_score");
        diagnostics.put("reranker_status", ranking.status().name());
        diagnostics.put("context_tokens", tokens);
        if (adjacent) {
            diagnostics.put("score_type", "ADJACENT_CONTEXT");
        } else if (ranking.status() == DocumentReranker.Status.SUCCESS) {
            diagnostics.put("reranker_score", ranking.scores().get(document.getId()));
        }
        Map<String, Object> metadata = new LinkedHashMap<>(document.getMetadata());
        metadata.put(HybridDocumentRetriever.DIAGNOSTICS_KEY, Map.copyOf(diagnostics));
        // Keep Document.score in its original domain: cosine for FAST, RRF for BALANCED.
        return document.mutate().metadata(metadata).build();
    }
}
