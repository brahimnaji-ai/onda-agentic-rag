package ma.onda.rag.agent.application.retrieval;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import ma.onda.rag.agent.infra.springai.OndaDocumentPostProcessor;
import ma.onda.rag.agent.infra.springai.PostRetrievalProperties;
import ma.onda.rag.document.infra.VectorMetadataKeys;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.rag.Query;
import org.springframework.ai.rag.preretrieval.query.transformation.QueryTransformer;
import org.springframework.ai.rag.retrieval.join.ConcatenationDocumentJoiner;
import org.springframework.ai.rag.retrieval.search.DocumentRetriever;
import org.springframework.ai.rag.retrieval.search.VectorStoreDocumentRetriever;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class OndaRetrievalPipelineTest {

    @Test
    void keepsPreselectionCandidatesForRecallAndFallsBackWhenOnlyAnExtraSearchFails() {
        List<Document> candidates = java.util.stream.IntStream.range(0, 25)
                .mapToObj(i -> document("candidate-" + i, "evidence " + i, 1.0 - i * .01)).toList();
        var pipeline = new OndaRetrievalPipeline(List.of(), query -> List.of(new Query("extra")), query -> {
            if (query.text().equals("extra")) throw new IllegalStateException("extra arm offline");
            return candidates;
        }, new ConcatenationDocumentJoiner(), List.of(new OndaDocumentPostProcessor(new PostRetrievalProperties(4))), meters);
        var result = pipeline.retrieve(new RetrievalRequest("original"));
        assertThat(result.candidateChunkIds()).hasSize(20).contains("candidate-19").doesNotContain("candidate-20");
        assertThat(result.documents()).hasSize(4);
        assertThat(result.expansion().status()).isEqualTo("RETRIEVAL_ERROR");
        assertThat(result.expansion().fallback()).isTrue();
    }

    private final VectorStore vectorStore = mock(VectorStore.class);
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final OndaRetrievalPipeline fast = new OndaRetrievalPipeline(List.of(), List::of,
            VectorStoreDocumentRetriever.builder().vectorStore(vectorStore).topK(8).similarityThreshold(0.5).build(),
            new ConcatenationDocumentJoiner(),
            List.of(new OndaDocumentPostProcessor(new PostRetrievalProperties(2))), meters);

    @Test
    void fastRetrievesDeduplicatesAndCapsEvidenceWithExplicitSourcesQueriesAndTimings() {
        Document first = document("1", "Conditions d'accès à l'aérogare", 0.95);
        Document duplicate = document("1", "  Conditions d'accès   à l'aérogare  ", 0.9);
        Document second = document("3", "Horaires de l'aéroport Mohammed V", 0.8);
        Document third = document("4", "Services aux passagers", 0.7);
        when(vectorStore.similaritySearch(any(SearchRequest.class)))
                .thenReturn(List.of(first, duplicate, second, third, document("5", " ", 0.6)));

        RetrievalResult result = fast.retrieve(new RetrievalRequest("accès CMN"));

        var captor = org.mockito.ArgumentCaptor.forClass(SearchRequest.class);
        verify(vectorStore).similaritySearch(captor.capture());
        assertThat(captor.getValue().getQuery()).isEqualTo("accès CMN");
        assertThat(captor.getValue().getTopK()).isEqualTo(8);
        assertThat(captor.getValue().getSimilarityThreshold()).isEqualTo(0.5);
        assertThat(result.documents()).extracting(Document::getId).containsExactly("1", "3");
        assertThat(result.sources()).containsExactly(
                new RetrievalResult.Source("1", "doc-1", "guide.pdf", first.getText(), 0.95),
                new RetrievalResult.Source("3", "doc-3", "guide.pdf", second.getText(), 0.8));
        assertThat(result.executedQueries()).containsExactly("accès CMN");
        assertThat(result.profile()).isEqualTo(RetrievalProfile.FAST);
        assertThat(result.timings()).containsOnlyKeys(RetrievalStage.values());
        assertThat(result.timings().values()).allMatch(duration -> !duration.isNegative());
        assertThat(result.timings().get(RetrievalStage.TOTAL)).isGreaterThanOrEqualTo(
                result.timings().get(RetrievalStage.RETRIEVE));
        meters.getMeters().forEach(meter -> assertThat(meter.getId().getTags())
                .extracting(Tag::getKey).containsExactlyInAnyOrder("profile", "stage", "outcome"));
        assertThatThrownBy(() -> result.documents().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> result.timings().clear()).isInstanceOf(UnsupportedOperationException.class);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = "   ")
    void invalidQueriesDoNotExecuteStrategies(String query) {
        RetrievalResult result = fast.retrieve(new RetrievalRequest(query));
        assertThat(result.documents()).isEmpty();
        assertThat(result.executedQueries()).isEmpty();
        assertThat(result.timings()).containsOnlyKeys(RetrievalStage.TOTAL);
        assertThat(fast.retrieve(null).sources()).isEmpty();
        verifyNoInteractions(vectorStore);
    }

    @Test
    void toleratesEmptyAndNullStoreResultsAndMissingSourceMetadata() {
        when(vectorStore.similaritySearch(any(SearchRequest.class)))
                .thenReturn(null).thenReturn(List.of()).thenReturn(List.of(new Document("Unlabelled evidence")));
        assertThat(fast.retrieve(new RetrievalRequest("query")).documents()).isEmpty();
        assertThat(fast.retrieve(new RetrievalRequest("query")).sources()).isEmpty();
        RetrievalResult result = fast.retrieve(new RetrievalRequest("query"));
        assertThat(result.sources().getFirst().documentId()).isNull();
        assertThat(result.sources().getFirst().sourceFilename()).isNull();
        assertThat(result.sources().getFirst().relevanceScore()).isNull();
    }

    @Test
    void executesStrategyChainsInOrderAndKeepsRequestContextAcrossExpandedQueries() {
        List<String> calls = new ArrayList<>();
        Map<String, Object> context = Map.of(VectorStoreDocumentRetriever.FILTER_EXPRESSION, "uploaded_by == 'owner'");
        QueryTransformer first = query -> { calls.add("transform1"); return new Query("réécrit"); };
        QueryTransformer second = query -> {
            calls.add("transform2");
            assertThat(query.text()).isEqualTo("réécrit");
            return new Query("CMN");
        };
        Document one = document("1", "first evidence", 0.9);
        Document two = document("2", "second evidence", 0.8);
        OndaRetrievalPipeline pipeline = new OndaRetrievalPipeline(List.of(first, second), query -> {
            calls.add("expand");
            return List.of(query, new Query("Casablanca"), query);
        }, query -> {
            calls.add("retrieve:" + query.text());
            assertThat(query.context()).isEqualTo(context);
            return List.of(query.text().equals("Casablanca") ? two : one);
        }, candidates -> {
            calls.add("join");
            return new ConcatenationDocumentJoiner().join(candidates);
        }, List.of((query, documents) -> {
            calls.add("post1");
            assertThat(query.text()).isEqualTo("original");
            assertThat(documents).containsExactly(one, two);
            return documents.subList(1, 2);
        }, (query, documents) -> {
            calls.add("post2");
            assertThat(documents).containsExactly(two);
            return documents;
        }), meters);

        RetrievalResult result = pipeline.retrieve(new RetrievalRequest("original", RetrievalProfile.FAST, context));

        assertThat(calls).containsExactly("transform1", "transform2", "expand", "retrieve:original", "retrieve:CMN",
                "retrieve:Casablanca", "join", "post1", "post2");
        assertThat(result.executedQueries()).containsExactly("original", "CMN", "Casablanca");
        assertThat(result.documents()).containsExactly(two);
    }

    @Test
    void fallsBackWhenOptionalTransformationOrExpansionReturnsNoQuery() {
        DocumentRetriever retriever = mock(DocumentRetriever.class);
        when(retriever.retrieve(any())).thenReturn(List.of());
        var pipeline = new OndaRetrievalPipeline(List.of(query -> null), query -> List.of(), retriever,
                new ConcatenationDocumentJoiner(), List.of(), meters);
        assertThat(pipeline.retrieve(new RetrievalRequest("original")).executedQueries()).containsExactly("original");
        verify(retriever).retrieve(new Query("original"));
    }

    @Test
    void recordsFailedStagesWithoutLoggingSensitiveQueryOrExceptionText() {
        String secret = "sensitive-query-and-document-id";
        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenThrow(new IllegalStateException(secret));
        Logger logger = (Logger) LoggerFactory.getLogger(OndaRetrievalPipeline.class);
        Level previous = logger.getLevel();
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        logger.setLevel(Level.DEBUG);
        try {
            assertThatThrownBy(() -> fast.retrieve(new RetrievalRequest(secret))).isInstanceOf(IllegalStateException.class);
            for (RetrievalStage stage : List.of(RetrievalStage.RETRIEVE, RetrievalStage.TOTAL)) {
                assertThat(meters.get("rag.retrieval.stage").tags("profile", "FAST", "stage", stage.name(),
                        "outcome", "error").timer().count()).isEqualTo(1);
            }
            assertThat(appender.list).isNotEmpty().allSatisfy(event -> {
                assertThat(event.getFormattedMessage()).doesNotContain(secret);
                assertThat(event.getThrowableProxy()).isNull();
            });
        } finally {
            logger.detachAppender(appender);
            logger.setLevel(previous);
            appender.stop();
        }
    }

    private Document document(String id, String text, double score) {
        return Document.builder().id(id).text(text).score(score)
                .metadata(Map.of(VectorMetadataKeys.DOCUMENT_ID, "doc-" + id, VectorMetadataKeys.SOURCE, "guide.pdf"))
                .build();
    }
}
