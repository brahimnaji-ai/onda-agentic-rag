package ma.onda.rag.agent.infra.springai;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import ma.onda.rag.agent.application.retrieval.DocumentReranker;
import ma.onda.rag.agent.application.retrieval.EvidenceBudget;
import org.springframework.ai.document.Document;
import org.springframework.ai.rag.Query;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OndaDocumentPostProcessorTest {

    @Test
    void deduplicatesByStableIdBeforeBulkScoringButKeepsIdenticalTextFromDifferentSources() {
        Document first = chunk("a", "Conditions d'accès", 0.03);
        Document duplicate = chunk("a", "Duplicate with weaker recall", 0.01);
        Document second = chunk("b", "Conditions d'accès", 0.02);
        var processor = processor(2, 4096, (query, candidates) -> {
            assertThat(query).isEqualTo("مطار Casablanca");
            assertThat(candidates).containsExactly(new DocumentReranker.Candidate("a", first.getText()),
                    new DocumentReranker.Candidate("b", second.getText()));
            return new DocumentReranker.Result(Map.of("a", 0.2, "b", 0.9), DocumentReranker.Status.SUCCESS);
        });
        var selected = processor.process(new Query("مطار Casablanca"),
                java.util.Arrays.asList(second, duplicate, first, null, chunk("blank", " ", 0.1)));
        assertThat(selected).extracting(Document::getId).containsExactly("b", "a");
        assertThat(selected.getFirst().getMetadata()).containsEntry("source", "b.pdf").containsEntry("page", 2);
        assertThat(selected.getFirst().getScore()).isEqualTo(0.02);
        assertThat(diagnostics(selected.getFirst())).containsEntry("rrf_score", 0.02)
                .containsEntry("dense_score", 0.8).containsEntry("lexical_rank", 3)
                .containsEntry("reranker_score", 0.9).containsEntry("reranker_status", "SUCCESS");
        assertThat(diagnostics(second)).doesNotContainKey("reranker_score");
    }

    @Test
    void equalRerankerScoresRetainDeterministicRrfOrderAndFinalCount() {
        var processor = processor(2, 4096, (q, docs) -> new DocumentReranker.Result(
                Map.of("a", 0.5, "b", 0.5, "c", 0.5), DocumentReranker.Status.SUCCESS));
        assertThat(processor.process(new Query("q"), List.of(chunk("c", "third", 0.01),
                chunk("b", "second", 0.02), chunk("a", "first", 0.02))))
                .extracting(Document::getId).containsExactly("a", "b");
    }

    @Test
    void enforcesExactBudgetBoundaryIncludingCitationFieldsAndEscapedMultilingualText() {
        Document small = chunk("small", "العربية: accès \"CMN\"\n✈️", 0.02);
        Document oversized = chunk("large", "x".repeat(5000), 0.03);
        int budget = EvidenceBudget.tokens(small) + EvidenceBudget.ENVELOPE_TOKENS;
        var disabled = (DocumentReranker) (q, docs) -> DocumentReranker.Result.unavailable(DocumentReranker.Status.DISABLED);
        assertThat(processor(4, budget, disabled).process(new Query("q"), List.of(oversized, small)))
                .extracting(Document::getId).containsExactly("small");
        assertThat(processor(4, budget - 1, disabled).process(new Query("q"), List.of(small))).isEmpty();
        assertThat(processor(4, 1, disabled).process(new Query("q"), List.of(small))).isEmpty();
    }

    @ParameterizedTest
    @EnumSource(value = DocumentReranker.Status.class, names = {"TIMEOUT", "ERROR", "CIRCUIT_OPEN", "BULKHEAD_FULL", "INTERRUPTED", "DISABLED"})
    void unavailableRerankerPreservesUsefulRrfEvidence(DocumentReranker.Status status) {
        var selected = processor(2, 4096, (q, docs) -> DocumentReranker.Result.unavailable(status))
                .process(new Query("q"), List.of(chunk("b", "B", 0.01), chunk("a", "A", 0.03)));
        assertThat(selected).extracting(Document::getId).containsExactly("a", "b");
        assertThat(diagnostics(selected.getFirst())).containsEntry("reranker_status", status.name())
                .doesNotContainKey("reranker_score");
    }

    @Test
    void exceptionsAndPartialScoresFallBackWithoutDroppingCandidates() {
        List<Document> input = List.of(chunk("a", "A", 0.03), chunk("b", "B", 0.01));
        var failed = processor(2, 4096, (q, docs) -> { throw new IllegalStateException("offline"); });
        var partial = processor(2, 4096, (q, docs) -> new DocumentReranker.Result(Map.of("a", 1.0), DocumentReranker.Status.SUCCESS));
        for (var processor : List.of(failed, partial)) {
            var selected = processor.process(new Query("q"), input);
            assertThat(selected).extracting(Document::getId).containsExactly("a", "b");
            assertThat(diagnostics(selected.getFirst())).containsEntry("reranker_status", "ERROR");
        }
    }

    @Test
    void expandsOnlySelectedAnchorsAfterBudgetingAndNeverOverflowsOrDuplicates() {
        Document anchor = chunk("a", "anchor", 0.03);
        Document neighbor = chunk("b", "neighbor", 0.01);
        int budget = EvidenceBudget.ENVELOPE_TOKENS + EvidenceBudget.tokens(anchor) + EvidenceBudget.tokens(neighbor);
        var processor = new OndaDocumentPostProcessor(new PostRetrievalProperties(3, budget, true),
                (q, candidates) -> {
                    assertThat(candidates).extracting(DocumentReranker.Candidate::chunkId).containsExactly("a");
                    return new DocumentReranker.Result(Map.of("a", 0.9), DocumentReranker.Status.SUCCESS);
                }, (q, selected) -> {
                    assertThat(selected.getId()).isEqualTo("a");
                    assertThat(q.context()).containsEntry("filter", "retained");
                    return List.of(anchor, neighbor, chunk("c", "x".repeat(2000), 0.0));
                });
        var selected = processor.process(new Query("q", List.of(), Map.of("filter", "retained")), List.of(anchor));
        assertThat(selected).extracting(Document::getId).containsExactly("a", "b");
        assertThat(selected.getLast().getMetadata()).containsEntry("source", "b.pdf").containsEntry("page", 2);
        assertThat(diagnostics(selected.getLast())).containsEntry("score_type", "ADJACENT_CONTEXT")
                .doesNotContainKey("reranker_score");
        assertThat(selected.stream().mapToInt(EvidenceBudget::tokens).sum() + EvidenceBudget.ENVELOPE_TOKENS).isEqualTo(budget);
    }

    @Test
    void emptyInputDoesNotInvokeProviderAndInvalidBudgetsFailFast() {
        var processor = processor(2, 4096, (q, docs) -> { throw new AssertionError("must not call provider"); });
        assertThat(processor.process(new Query("q"), null)).isEmpty();
        assertThat(processor.process(new Query("q"), List.of())).isEmpty();
        assertThat(processor.process(new Query("q"), List.of(chunk("blank", " ", 1.0)))).isEmpty();
        assertThatThrownBy(() -> new PostRetrievalProperties(0, 100, false)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PostRetrievalProperties(1, 0, false)).isInstanceOf(IllegalArgumentException.class);
    }

    private static OndaDocumentPostProcessor processor(int count, int budget, DocumentReranker reranker) {
        return new OndaDocumentPostProcessor(new PostRetrievalProperties(count, budget, false), reranker,
                (q, anchor) -> { throw new AssertionError("expansion disabled"); });
    }

    private static Document chunk(String id, String text, double rrf) {
        return Document.builder().id(id).text(text).score(rrf).metadata(Map.of("document_id", "doc-" + id,
                "source", id + ".pdf", "page", 2, "retrieval", Map.of("rrf_score", rrf,
                        "dense_score", 0.8, "lexical_rank", 3))).build();
    }

    private static Map<String, Object> diagnostics(Document document) {
        var result = new java.util.HashMap<String, Object>();
        ((Map<?, ?>) document.getMetadata().get("retrieval")).forEach((k, v) -> result.put(k.toString(), v));
        return result;
    }
}
