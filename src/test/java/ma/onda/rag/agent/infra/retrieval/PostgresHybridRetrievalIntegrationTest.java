package ma.onda.rag.agent.infra.retrieval;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import ma.onda.rag.agent.application.retrieval.*;
import ma.onda.rag.agent.infra.springai.OndaDocumentPostProcessor;
import ma.onda.rag.agent.infra.springai.PostRetrievalProperties;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.rag.Query;
import org.springframework.ai.rag.retrieval.join.ConcatenationDocumentJoiner;
import org.springframework.ai.rag.retrieval.search.VectorStoreDocumentRetriever;
import org.springframework.ai.vectorstore.filter.Filter;
import org.springframework.ai.vectorstore.filter.FilterExpressionTextParser;
import org.springframework.ai.vectorstore.pgvector.PgVectorStore;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.json.JsonMapper;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@Testcontainers
class PostgresHybridRetrievalIntegrationTest {

    @Container
    static final PostgreSQLContainer postgres = new PostgreSQLContainer(DockerImageName.parse("pgvector/pgvector:pg17"));

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final ExecutorService EXECUTOR = Executors.newVirtualThreadPerTaskExecutor();
    private static final SimpleMeterRegistry METERS = new SimpleMeterRegistry();
    private static JdbcClient jdbc;
    private static Flyway flyway;
    private static Fixture fixture;
    private static PostgresFullTextDocumentRetriever lexical;
    private static OndaRetrievalPipeline pipeline;

    @BeforeAll
    static void migratePopulatedLegacySchema() throws Exception {
        var dataSource = new DriverManagerDataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        jdbc = JdbcClient.create(dataSource);
        jdbc.sql("CREATE EXTENSION vector").update();
        // The pre-ticket schema deliberately has no search column or Flyway history.
        jdbc.sql("""
                CREATE TABLE public.vector_store (
                    id UUID PRIMARY KEY, content TEXT NOT NULL, metadata JSONB NOT NULL DEFAULT '{}',
                    embedding VECTOR(768) NOT NULL
                )
                """).update();
        try (var input = new ClassPathResource("retrieval/hybrid-fixtures.json").getInputStream()) {
            fixture = JSON.readValue(input, Fixture.class);
        }
        for (Chunk chunk : fixture.chunks()) {
            insert(chunk, "fixture-owner");
        }
        flyway = Flyway.configure().dataSource(dataSource).baselineOnMigrate(true).baselineVersion("0").load();
        assertThat(flyway.migrate().migrationsExecuted).isEqualTo(1);

        EmbeddingModel embeddings = mock(EmbeddingModel.class);
        when(embeddings.embed(anyString())).thenReturn(vector(true));
        var vectorStore = PgVectorStore.builder(new JdbcTemplate(dataSource), embeddings)
                .dimensions(768).initializeSchema(false).build();
        var fastDense = VectorStoreDocumentRetriever.builder().vectorStore(vectorStore)
                .topK(8).similarityThreshold(0.5).build();
        var balancedDense = VectorStoreDocumentRetriever.builder().vectorStore(vectorStore)
                .topK(20).similarityThreshold(0.0).build();
        lexical = new PostgresFullTextDocumentRetriever(jdbc, 20);
        var post = List.of(new OndaDocumentPostProcessor(new PostRetrievalProperties(4)));
        pipeline = new OndaRetrievalPipeline(Map.of(
                RetrievalProfile.FAST, new RetrievalPlan(List.of(), List::of, fastDense,
                        new ConcatenationDocumentJoiner(), List.copyOf(post)),
                RetrievalProfile.BALANCED, new RetrievalPlan(List.of(), List::of,
                        new HybridDocumentRetriever(balancedDense, lexical, EXECUTOR, 60),
                        new RankedDocumentJoiner(), List.copyOf(post))), RetrievalProfile.FAST, METERS);
    }

    @AfterAll
    static void closeResources() {
        EXECUTOR.close();
        METERS.close();
    }

    @Test
    void migrationPreservesExistingChunksBuildsTheIndexAndDoesNotRerun() {
        assertThat(jdbc.sql("SELECT count(*) FROM public.vector_store").query(Long.class).single()).isEqualTo(6);
        assertThat(jdbc.sql("SELECT count(*) FROM public.vector_store WHERE search_vector IS NOT NULL")
                .query(Long.class).single()).isEqualTo(6);
        assertThat(jdbc.sql("""
                SELECT is_generated FROM information_schema.columns
                WHERE table_schema = 'public' AND table_name = 'vector_store' AND column_name = 'search_vector'
                """).query(String.class).single()).isEqualTo("ALWAYS");
        assertThat(jdbc.sql("SELECT indexdef FROM pg_indexes WHERE indexname = 'idx_vector_store_search_vector_gin'")
                .query(String.class).single()).contains("USING gin (search_vector)");
        assertThat(flyway.migrate().migrationsExecuted).isZero();
        flyway.validate();
    }

    @Test
    void lexicalArmFindsIdentifiersFilenamesDatesCodesAndFrenchWordForms() {
        fixture.queries().stream().filter(label -> !label.category().equals("semantic-paraphrase")).forEach(label -> {
            List<Document> documents = lexical.retrieve(new Query(label.query()));
            assertThat(documents).as(label.category()).extracting(Document::getId).contains(label.relevantId());
            assertThat(documents).allSatisfy(document -> assertThat(document.getMetadata())
                    .containsEntry("uploaded_by", "fixture-owner").containsKeys("document_id", "source"));
        });
    }

    @Test
    void balancedImprovesLabeledRecallAndPreservesDenseSemanticMatches() {
        int denseHits = 0;
        int hybridHits = 0;
        for (Label label : fixture.queries()) {
            RetrievalResult fast = pipeline.retrieve(new RetrievalRequest(label.query(), RetrievalProfile.FAST, Map.of()));
            RetrievalResult balanced = pipeline.retrieve(new RetrievalRequest(label.query(), RetrievalProfile.BALANCED, Map.of()));
            if (fast.documents().stream().anyMatch(document -> document.getId().equals(label.relevantId()))) denseHits++;
            if (balanced.documents().stream().anyMatch(document -> document.getId().equals(label.relevantId()))) hybridHits++;
            assertThat(balanced.documents()).as(label.category()).extracting(Document::getId).contains(label.relevantId());
            assertThat(balanced.documents()).allSatisfy(document -> {
                assertThat(document.getScore()).isBetween(0.0, 2.0 / 61);
                assertThat(((Map<?, ?>) document.getMetadata().get(HybridDocumentRetriever.DIAGNOSTICS_KEY))
                        .get("score_type")).isEqualTo("RRF");
            });
            assertThat(balanced.profile()).isEqualTo(RetrievalProfile.BALANCED);
            assertThat(balanced.timings()).containsOnlyKeys(RetrievalStage.values());
        }
        // Fixed synthetic embeddings deliberately miss lexical facts; this is not a model benchmark.
        assertThat(denseHits).isEqualTo(1);
        assertThat(hybridHits).isEqualTo(fixture.queries().size()).isGreaterThan(denseHits);
    }

    @Test
    void generatedSearchRepresentationTracksInsertsContentAndFilenameUpdates() {
        Chunk extra = new Chunk("00000000-0000-0000-0000-000000000090", "ancien contenu", "ancien.pdf", false);
        insert(extra, "fixture-owner");
        try {
            jdbc.sql("UPDATE public.vector_store SET content = :content, metadata = metadata || CAST(:metadata AS jsonb) WHERE id = CAST(:id AS uuid)")
                    .param("content", "Nouvelle consigne ZEBRA901")
                    .param("metadata", "{\"source\":\"nouveau_plan.pdf\"}").param("id", extra.id()).update();
            assertThat(lexical.retrieve(new Query("ZEBRA901"))).extracting(Document::getId).containsExactly(extra.id());
            assertThat(lexical.retrieve(new Query("nouveau_plan.pdf"))).extracting(Document::getId).containsExactly(extra.id());
            assertThat(lexical.retrieve(new Query("ancien.pdf"))).isEmpty();
        } finally {
            delete(extra.id());
        }
    }

    @Test
    void bothArmsHonorStringAndStructuredMetadataFiltersIncludingQuotes() {
        Chunk extra = new Chunk("00000000-0000-0000-0000-000000000091", "CMN", "autre.pdf", true);
        insert(extra, "o'connor");
        try {
            for (Object filter : List.of("uploaded_by == 'fixture-owner'",
                    new FilterExpressionTextParser().parse("uploaded_by == 'fixture-owner'"))) {
                var result = pipeline.retrieve(new RetrievalRequest("CMN", RetrievalProfile.BALANCED,
                        Map.of(VectorStoreDocumentRetriever.FILTER_EXPRESSION, filter)));
                assertThat(result.documents()).isNotEmpty().allSatisfy(document -> assertThat(document.getMetadata())
                        .containsEntry("uploaded_by", "fixture-owner"));
            }
            var quotedFilter = new Filter.Expression(Filter.ExpressionType.EQ,
                    new Filter.Key("uploaded_by"), new Filter.Value("o'connor"));
            assertThat(lexical.retrieve(new Query("CMN", List.of(),
                    Map.of(VectorStoreDocumentRetriever.FILTER_EXPRESSION, quotedFilter))))
                    .extracting(Document::getId).containsExactly(extra.id());
        } finally {
            delete(extra.id());
        }
    }

    @Test
    void lexicalCandidateLimitAndTiedRanksAreStableOnAFixedCorpus() {
        List<Chunk> chunks = List.of(
                new Chunk("00000000-0000-0000-0000-000000000093", "RRFTEST901 scenario93", "tie.pdf", false),
                new Chunk("00000000-0000-0000-0000-000000000092", "RRFTEST901 scenario92", "tie.pdf", false),
                new Chunk("00000000-0000-0000-0000-000000000091", "RRFTEST901 scenario91", "tie.pdf", false));
        try {
            chunks.forEach(chunk -> insert(chunk, "fixture-owner"));
            var limited = new PostgresFullTextDocumentRetriever(jdbc, 2);
            for (int attempt = 0; attempt < 5; attempt++) {
                assertThat(limited.retrieve(new Query("RRFTEST901"))).extracting(Document::getId)
                        .containsExactly(chunks.get(2).id(), chunks.get(1).id());
                assertThat(pipeline.retrieve(new RetrievalRequest("RRFTEST901", RetrievalProfile.BALANCED, Map.of()))
                        .documents()).extracting(Document::getId)
                        .containsExactly(fixture.chunks().get(5).id(), chunks.get(2).id(), chunks.get(1).id(), chunks.get(0).id());
            }
        } finally {
            chunks.forEach(chunk -> delete(chunk.id()));
        }
    }

    @Test
    void treatsSearchTextAsDataAndReturnsNothingForStopWords() {
        assertThat(lexical.retrieve(new Query("le la et"))).isEmpty();
        assertThat(lexical.retrieve(new Query("'; DROP TABLE public.vector_store; --"))).isEmpty();
        assertThat(jdbc.sql("SELECT count(*) FROM public.vector_store").query(Long.class).single()).isEqualTo(6);
    }

    private static void insert(Chunk chunk, String owner) {
        jdbc.sql("""
                INSERT INTO public.vector_store (id, content, metadata, embedding)
                VALUES (CAST(:id AS uuid), :content, CAST(:metadata AS jsonb), CAST(:embedding AS vector))
                """).param("id", chunk.id()).param("content", chunk.content())
                .param("metadata", JSON.writeValueAsString(Map.of("document_id", chunk.id(), "source", chunk.filename(), "uploaded_by", owner)))
                .param("embedding", Arrays.toString(vector(chunk.semantic()))).update();
    }

    @Test
    void adjacentChunksStayWithinDocumentOwnerAndOriginalFilter() {
        var extra = List.of(
                new Chunk("00000000-0000-0000-0000-000000000080", "previous", "neighbors.pdf", false),
                new Chunk("00000000-0000-0000-0000-000000000081", "next", "neighbors.pdf", false),
                new Chunk("00000000-0000-0000-0000-000000000082", "other owner", "neighbors.pdf", false),
                new Chunk("00000000-0000-0000-0000-000000000083", "other document", "neighbors.pdf", false),
                new Chunk("00000000-0000-0000-0000-000000000084", "not adjacent", "neighbors.pdf", false));
        try {
            for (int i = 0; i < extra.size(); i++) {
                insert(extra.get(i), i == 2 ? "other-owner" : "fixture-owner");
                jdbc.sql("UPDATE public.vector_store SET metadata = metadata || CAST(:metadata AS jsonb) WHERE id = CAST(:id AS uuid)")
                        .param("id", extra.get(i).id())
                        .param("metadata", JSON.writeValueAsString(Map.of("document_id", i == 3 ? "other-doc" : "parent",
                                "chunk_index", i == 0 ? 0 : i == 4 ? 8 : 2))).update();
            }
            var anchor = Document.builder().id("anchor").text("anchor").metadata(Map.of("document_id", "parent",
                    "uploaded_by", "fixture-owner", "chunk_index", 1)).build();
            var neighbors = new PostgresAdjacentChunkRetriever(jdbc);
            assertThat(neighbors.retrieve(new Query("q"), anchor)).extracting(Document::getId)
                    .containsExactly(extra.get(0).id(), extra.get(1).id());
            var excluded = new Query("q", List.of(), Map.of(VectorStoreDocumentRetriever.FILTER_EXPRESSION,
                    "source == 'excluded.pdf'"));
            assertThat(neighbors.retrieve(excluded, anchor)).isEmpty();
            assertThat(neighbors.retrieve(new Query("q"), new Document("no chunk index"))).isEmpty();
        } finally {
            extra.forEach(chunk -> delete(chunk.id()));
        }
    }

    private static void delete(String id) {
        jdbc.sql("DELETE FROM public.vector_store WHERE id = CAST(:id AS uuid)").param("id", id).update();
    }

    private static float[] vector(boolean semantic) {
        float[] vector = new float[768];
        vector[semantic ? 0 : 1] = 1;
        return vector;
    }

    record Fixture(List<Chunk> chunks, List<Label> queries) {}
    record Chunk(String id, String content, String filename, boolean semantic) {}
    record Label(String category, String query, String relevantId) {}
}
