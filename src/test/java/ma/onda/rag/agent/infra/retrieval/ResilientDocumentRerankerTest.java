package ma.onda.rag.agent.infra.retrieval;

import ma.onda.rag.agent.application.retrieval.DocumentReranker;
import ma.onda.rag.agent.infra.springai.OndaDocumentPostProcessor;
import ma.onda.rag.agent.infra.springai.PostRetrievalProperties;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.rag.Query;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

class ResilientDocumentRerankerTest {
    private static final List<DocumentReranker.Candidate> CANDIDATES = List.of(new DocumentReranker.Candidate("a", "texte"));
    private static final DocumentReranker.Result SCORED = new DocumentReranker.Result(Map.of("a", 0.9), DocumentReranker.Status.SUCCESS);

    @Test
    void timeoutReturnsRrfEvidenceAndDoesNotFreeSlotsForAnUncooperativeProvider() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();
        try (var reranker = new ResilientDocumentReranker((query, candidates) -> {
            calls.incrementAndGet();
            entered.countDown();
            awaitUninterruptibly(release);
            return SCORED;
        }, Duration.ofMillis(150), 1, 5, Duration.ofSeconds(30))) {
            var processor = new OndaDocumentPostProcessor(new PostRetrievalProperties(2), reranker, (q, doc) -> List.of());
            var input = List.of(Document.builder().id("b").text("B").score(0.01).build(),
                    Document.builder().id("a").text("A").score(0.03).build());
            var output = assertTimeoutPreemptively(Duration.ofSeconds(3), () -> processor.process(new Query("q"), input));
            assertThat(entered.await(1, TimeUnit.SECONDS)).isTrue();
            assertThat(output).extracting(Document::getId).containsExactly("a", "b");
            assertThat(((Map<?, ?>) output.getFirst().getMetadata().get("retrieval")).get("reranker_status")).isEqualTo("TIMEOUT");
            assertThat(reranker.rerank("q", CANDIDATES).status()).isEqualTo(DocumentReranker.Status.BULKHEAD_FULL);
            assertThat(calls).hasValue(1);
        } finally {
            release.countDown();
        }
    }

    @Test
    void failuresOpenCircuitAndOnlyOneHalfOpenProbeCanRecoverIt() throws Exception {
        AtomicLong clock = new AtomicLong();
        AtomicInteger calls = new AtomicInteger();
        CountDownLatch probing = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try (var callers = Executors.newVirtualThreadPerTaskExecutor();
             var reranker = new ResilientDocumentReranker((query, candidates) -> {
                 if (calls.incrementAndGet() <= 2) throw new IllegalStateException("offline");
                 probing.countDown();
                 awaitUninterruptibly(release);
                 return SCORED;
             }, Duration.ofSeconds(3), 4, 2, Duration.ofSeconds(30), clock::get)) {
            assertThat(reranker.rerank("q", CANDIDATES).status()).isEqualTo(DocumentReranker.Status.ERROR);
            assertThat(reranker.rerank("q", CANDIDATES).status()).isEqualTo(DocumentReranker.Status.ERROR);
            assertThat(reranker.rerank("q", CANDIDATES).status()).isEqualTo(DocumentReranker.Status.CIRCUIT_OPEN);
            assertThat(calls).hasValue(2);
            clock.set(Duration.ofSeconds(30).toNanos());
            var probe = callers.submit(() -> reranker.rerank("q", CANDIDATES));
            assertThat(probing.await(1, TimeUnit.SECONDS)).isTrue();
            assertThat(reranker.rerank("q", CANDIDATES).status()).isEqualTo(DocumentReranker.Status.CIRCUIT_OPEN);
            release.countDown();
            assertThat(probe.get(1, TimeUnit.SECONDS)).isEqualTo(SCORED);
            assertThat(reranker.rerank("q", CANDIDATES)).isEqualTo(SCORED);
        } finally {
            release.countDown();
        }
    }

    @Test
    void incompleteScoresTripCircuitRatherThanPassingPartialEvidence() {
        try (var reranker = new ResilientDocumentReranker((q, docs) -> new DocumentReranker.Result(Map.of(), DocumentReranker.Status.SUCCESS),
                Duration.ofSeconds(1), 2, 1, Duration.ofSeconds(30))) {
            assertThat(reranker.rerank("q", CANDIDATES).status()).isEqualTo(DocumentReranker.Status.ERROR);
            assertThat(reranker.rerank("q", CANDIDATES).status()).isEqualTo(DocumentReranker.Status.CIRCUIT_OPEN);
        }
    }

    @Test
    void interruptionIsPreservedAndFallsBack() {
        CountDownLatch release = new CountDownLatch(1);
        try (var reranker = new ResilientDocumentReranker((q, docs) -> {
            awaitUninterruptibly(release);
            return SCORED;
        }, Duration.ofSeconds(1), 1, 3, Duration.ofSeconds(30))) {
            Thread.currentThread().interrupt();
            assertThat(reranker.rerank("q", CANDIDATES).status()).isEqualTo(DocumentReranker.Status.INTERRUPTED);
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            Thread.interrupted();
            release.countDown();
        }
    }

    private static void awaitUninterruptibly(CountDownLatch latch) {
        boolean interrupted = false;
        while (true) {
            try {
                latch.await();
                break;
            } catch (InterruptedException ignored) {
                interrupted = true;
            }
        }
        if (interrupted) Thread.currentThread().interrupt();
    }
}
