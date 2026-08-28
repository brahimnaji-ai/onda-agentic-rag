package ma.onda.rag.agent.infra.retrieval;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.ai.document.Document;
import org.springframework.ai.rag.Query;
import org.springframework.ai.rag.retrieval.search.DocumentRetriever;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.*;

class HybridDocumentRetrieverTest {

    @Test
    void combinesRanksWithoutComparingRawScoresAndPreservesSourceMetadata() {
        Document first = document("a", 0.99);
        Document overlap = document("b", 0.01);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var retriever = new HybridDocumentRetriever(query -> List.of(first, overlap),
                    query -> List.of(document("c", 9000), document("b", 3000)), executor, 60);

            List<Document> result = retriever.retrieve(new Query("CMN"));

            assertThat(result).extracting(Document::getId).containsExactly("b", "a", "c");
            assertThat(result.getFirst().getScore()).isEqualTo(2.0 / 62);
            assertThat(result.get(1).getScore()).isEqualTo(1.0 / 61);
            assertThat(result.get(2).getScore()).isEqualTo(1.0 / 61);
            assertThat(result.getFirst().getMetadata()).containsEntry("source", "b.pdf");
            assertThat(result.getFirst().getMetadata().get(HybridDocumentRetriever.DIAGNOSTICS_KEY))
                    .isEqualTo(Map.of("score_type", "RRF", "rrf_k", 60, "rrf_score", 2.0 / 62,
                            "dense_rank", 2, "lexical_rank", 2, "dense_score", 0.01, "lexical_score", 3000.0));
            assertThat(overlap.getScore()).isEqualTo(0.01);
            assertThat(overlap.getMetadata()).doesNotContainKey(HybridDocumentRetriever.DIAGNOSTICS_KEY);
            for (int attempt = 0; attempt < 10; attempt++) {
                assertThat(retriever.retrieve(new Query("CMN"))).extracting(Document::getId)
                        .containsExactly("b", "a", "c");
            }
        }
    }

    @Test
    void startsBothArmsConcurrentlyWithTheSameExplicitQueryContext() {
        CountDownLatch started = new CountDownLatch(2);
        Query input = new Query("CMN", List.of(), Map.of("filter", "owner"));
        DocumentRetriever arm = query -> {
            assertThat(query).isSameAs(input);
            started.countDown();
            await(started);
            return List.of(document("a", 1));
        };
        try (var executor = Executors.newFixedThreadPool(2)) {
            var retriever = new HybridDocumentRetriever(arm, arm, executor, 60);
            assertThat(retriever.retrieve(input)).singleElement()
                    .satisfies(document -> assertThat(document.getScore()).isEqualTo(2.0 / 61));
        }
    }

    @Test
    void deduplicatesEachArmAndHandlesMissingCandidatesWithoutChangingScoreType() {
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var retriever = new HybridDocumentRetriever(query -> List.of(document("a", 0.9), document("a", 0.5)),
                    query -> List.of(), executor, 10);
            assertThat(retriever.retrieve(new Query("CMN"))).singleElement().satisfies(document -> {
                assertThat(document.getScore()).isEqualTo(1.0 / 11);
                assertThat(((Map<?, ?>) document.getMetadata().get(HybridDocumentRetriever.DIAGNOSTICS_KEY))
                        .containsKey("lexical_rank")).isFalse();
            });
            assertThat(new HybridDocumentRetriever(query -> null, query -> List.of(), executor, 60)
                    .retrieve(new Query("CMN"))).isEmpty();
            assertThat(new HybridDocumentRetriever(query -> List.of(), query -> List.of(document("z", 800)), executor, 60)
                    .retrieve(new Query("CMN")).getFirst().getScore()).isEqualTo(1.0 / 61);
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void propagatesFailuresAndCancelsTheOtherArm(boolean denseFails) throws Exception {
        CountDownLatch peerStarted = new CountDownLatch(1);
        CountDownLatch peerCancelled = new CountDownLatch(1);
        IllegalStateException unavailable = new IllegalStateException("unavailable");
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            DocumentRetriever failing = query -> {
                await(peerStarted);
                throw unavailable;
            };
            DocumentRetriever blocking = query -> {
                peerStarted.countDown();
                try {
                    new CountDownLatch(1).await();
                } catch (InterruptedException interrupted) {
                    peerCancelled.countDown();
                    Thread.currentThread().interrupt();
                }
                return List.of();
            };
            var retriever = new HybridDocumentRetriever(denseFails ? failing : blocking,
                    denseFails ? blocking : failing, executor, 60);
            assertThatThrownBy(() -> retriever.retrieve(new Query("CMN"))).isSameAs(unavailable);
            assertThat(peerCancelled.await(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void preservesStableFusionOrderWhenJoiningExpandedQueries() {
        Map<Query, List<List<Document>>> results = new java.util.LinkedHashMap<>();
        results.put(new Query("first"), List.of(List.of(document("b", 0.02), document("a", 0.01))));
        results.put(new Query("second"), List.of(List.of(document("a", 0.02), document("c", 0.01))));
        List<Document> joined = new RankedDocumentJoiner().join(results);
        assertThat(joined).extracting(Document::getId).containsExactly("a", "b", "c");
        assertThat(joined.getFirst().getScore()).isEqualTo(0.02);
    }

    private static Document document(String id, double score) {
        return Document.builder().id(id).text("content " + id).score(score).metadata(Map.of("source", id + ".pdf")).build();
    }

    private static void await(CountDownLatch latch) {
        try {
            assertThat(latch.await(10, TimeUnit.SECONDS)).as("both retrieval arms must run concurrently").isTrue();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(interrupted);
        }
    }
}
