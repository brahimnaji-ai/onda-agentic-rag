package ma.onda.rag.agent.infra.retrieval;

import org.springframework.ai.document.Document;
import org.springframework.ai.rag.Query;
import org.springframework.ai.rag.retrieval.search.DocumentRetriever;
import org.springframework.ai.rag.retrieval.search.VectorStoreDocumentRetriever;
import org.springframework.ai.vectorstore.filter.Filter;
import org.springframework.ai.vectorstore.filter.FilterExpressionTextParser;
import org.springframework.ai.vectorstore.pgvector.PgVectorFilterExpressionConverter;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.util.Assert;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Map;

/** PostgreSQL lexical ranking, not BM25. All user search text is bound as a parameter. */
public final class PostgresFullTextDocumentRetriever implements DocumentRetriever {

    private static final TypeReference<Map<String, Object>> METADATA_TYPE = new TypeReference<>() {};
    private final JdbcClient jdbc;
    private final int candidateLimit;
    private final JsonMapper jsonMapper = JsonMapper.builder().build();

    public PostgresFullTextDocumentRetriever(JdbcClient jdbc, int candidateLimit) {
        Assert.notNull(jdbc, "jdbc cannot be null");
        Assert.isTrue(candidateLimit > 0, "candidateLimit must be positive");
        this.jdbc = jdbc;
        this.candidateLimit = candidateLimit;
    }

    @Override
    public List<Document> retrieve(Query query) {
        Assert.notNull(query, "query cannot be null");
        String sql = """
                WITH search_query AS (SELECT websearch_to_tsquery('french', :query) AS query)
                SELECT id, content, metadata, ts_rank_cd(search_vector, search_query.query) AS lexical_score
                FROM public.vector_store CROSS JOIN search_query
                WHERE search_vector @@ search_query.query
                """ + metadataFilter(query) + " ORDER BY lexical_score DESC, id ASC LIMIT :limit";
        return jdbc.sql(sql).param("query", query.text()).param("limit", candidateLimit)
                .query((rs, row) -> {
                    String json = rs.getString("metadata");
                    Map<String, Object> metadata = json == null ? Map.of() : jsonMapper.readValue(json, METADATA_TYPE);
                    return Document.builder().id(rs.getString("id")).text(rs.getString("content"))
                            .metadata(metadata).score(rs.getDouble("lexical_score")).build();
                }).list();
    }

    private String metadataFilter(Query query) {
        Object value = query.context().get(VectorStoreDocumentRetriever.FILTER_EXPRESSION);
        if (value == null || value.toString().isBlank()) {
            return "";
        }
        Filter.Expression expression = value instanceof Filter.Expression filter ? filter
                : new FilterExpressionTextParser().parse(value.toString());
        // Use the same validated/escaped SQL converter as PgVectorStore, never raw filter SQL.
        return " AND " + new PgVectorFilterExpressionConverter().convertExpression(expression);
    }
}
