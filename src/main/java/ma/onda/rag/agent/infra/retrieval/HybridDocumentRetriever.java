package ma.onda.rag.agent.infra.retrieval;

import org.springframework.ai.document.Document;
import org.springframework.ai.rag.Query;
import org.springframework.ai.rag.retrieval.search.DocumentRetriever;
import org.springframework.util.Assert;

import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;

/** Runs both candidate searches concurrently and compares ranks, never unlike raw scores. */
public final class HybridDocumentRetriever implements DocumentRetriever {

    public static final String DIAGNOSTICS_KEY = "retrieval";

    private final DocumentRetriever dense;
    private final DocumentRetriever lexical;
    private final ExecutorService executor;
    private final int rrfK;

    public HybridDocumentRetriever(DocumentRetriever dense, DocumentRetriever lexical,
                                   ExecutorService executor, int rrfK) {
        Assert.notNull(dense, "dense retriever cannot be null");
        Assert.notNull(lexical, "lexical retriever cannot be null");
        Assert.notNull(executor, "executor cannot be null");
        Assert.isTrue(rrfK > 0, "rrfK must be positive");
        this.dense = dense;
        this.lexical = lexical;
        this.executor = executor;
        this.rrfK = rrfK;
    }

    @Override
    public List<Document> retrieve(Query query) {
        var completion = new ExecutorCompletionService<ArmResult>(executor);
        Future<ArmResult> denseTask = completion.submit(() -> new ArmResult(true, dense.retrieve(query)));
        Future<ArmResult> lexicalTask = null;
        try {
            lexicalTask = completion.submit(() -> new ArmResult(false, lexical.retrieve(query)));
            // Observe either failure promptly, even if the other arm has not completed.
            ArmResult first = completion.take().get();
            ArmResult second = completion.take().get();
            return first.dense() ? fuse(first.documents(), second.documents())
                    : fuse(second.documents(), first.documents());
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Hybrid retrieval interrupted", failure);
        } catch (ExecutionException failure) {
            if (failure.getCause() instanceof RuntimeException cause) {
                throw cause;
            }
            throw new IllegalStateException("Hybrid candidate retrieval failed", failure.getCause());
        } finally {
            if (!denseTask.isDone()) {
                denseTask.cancel(true);
            }
            if (lexicalTask != null && !lexicalTask.isDone()) {
                lexicalTask.cancel(true);
            }
        }
    }

    private List<Document> fuse(List<Document> denseDocuments, List<Document> lexicalDocuments) {
        Map<String, Candidate> candidates = new HashMap<>();
        collect(candidates, denseDocuments, true);
        collect(candidates, lexicalDocuments, false);
        return candidates.values().stream().map(this::fusedDocument)
                .sorted(Comparator.comparingDouble((Document document) -> document.getScore()).reversed()
                        .thenComparing(Document::getId)).toList();
    }

    private void collect(Map<String, Candidate> candidates, List<Document> documents, boolean denseArm) {
        if (documents == null) {
            return;
        }
        var seen = new HashSet<String>();
        for (int index = 0; index < documents.size(); index++) {
            Document document = documents.get(index);
            if (!seen.add(document.getId())) {
                continue; // A repeated chunk contributes only once per arm.
            }
            Candidate candidate = candidates.computeIfAbsent(document.getId(), id -> new Candidate(document));
            if (denseArm) {
                candidate.denseRank = index + 1;
                candidate.denseScore = document.getScore();
            } else {
                candidate.lexicalRank = index + 1;
                candidate.lexicalScore = document.getScore();
            }
        }
    }

    private Document fusedDocument(Candidate candidate) {
        double score = contribution(candidate.denseRank) + contribution(candidate.lexicalRank);
        Map<String, Object> diagnostics = new LinkedHashMap<>();
        diagnostics.put("score_type", "RRF");
        diagnostics.put("rrf_score", score);
        diagnostics.put("rrf_k", rrfK);
        if (candidate.denseRank > 0) {
            diagnostics.put("dense_rank", candidate.denseRank);
            if (candidate.denseScore != null) diagnostics.put("dense_score", candidate.denseScore);
        }
        if (candidate.lexicalRank > 0) {
            diagnostics.put("lexical_rank", candidate.lexicalRank);
            if (candidate.lexicalScore != null) diagnostics.put("lexical_score", candidate.lexicalScore);
        }
        Map<String, Object> metadata = new LinkedHashMap<>(candidate.document.getMetadata());
        metadata.put(DIAGNOSTICS_KEY, Map.copyOf(diagnostics));
        return candidate.document.mutate().metadata(metadata).score(score).build();
    }

    private double contribution(int rank) {
        return rank == 0 ? 0 : 1.0 / ((double) rrfK + rank);
    }

    private record ArmResult(boolean dense, List<Document> documents) {}

    private static final class Candidate {
        private final Document document;
        private int denseRank;
        private int lexicalRank;
        private Double denseScore;
        private Double lexicalScore;

        private Candidate(Document document) {
            this.document = document;
        }
    }
}
