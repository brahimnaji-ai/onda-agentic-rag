package ma.onda.rag.agent.infra.retrieval;

import ma.onda.rag.document.infra.VectorMetadataKeys;
import org.springframework.ai.document.Document;
import org.springframework.ai.rag.Query;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Map;

/** Only immediate neighbors of the same document and owner, retaining the original query filter. */
public final class PostgresAdjacentChunkRetriever implements AdjacentChunkRetriever {
    private static final TypeReference<Map<String, Object>> METADATA_TYPE = new TypeReference<>() {};
    private final JdbcClient jdbc;
    private final JsonMapper json = JsonMapper.builder().build();

    public PostgresAdjacentChunkRetriever(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<Document> retrieve(Query query, Document anchor) {
        Object documentId = anchor.getMetadata().get(VectorMetadataKeys.DOCUMENT_ID);
        Object owner = anchor.getMetadata().get(VectorMetadataKeys.UPLOADED_BY);
        Object chunkIndex = anchor.getMetadata().get(VectorMetadataKeys.CHUNK_INDEX);
        if (documentId == null || !(chunkIndex instanceof Number index)) return List.of();
        String sql = """
                SELECT id, content, metadata FROM public.vector_store
                WHERE metadata->>'document_id' = :documentId
                  AND (metadata->>'uploaded_by') IS NOT DISTINCT FROM CAST(:owner AS text)
                  AND metadata->>'chunk_index' IN (:previous, :next)
                """ + PostgresFullTextDocumentRetriever.metadataFilter(query)
                + " ORDER BY metadata->>'chunk_index', id LIMIT 2";
        return jdbc.sql(sql).param("documentId", documentId.toString())
                .param("owner", owner == null ? null : owner.toString())
                .param("previous", Long.toString(index.longValue() - 1))
                .param("next", Long.toString(index.longValue() + 1))
                .query((rs, row) -> Document.builder().id(rs.getString("id")).text(rs.getString("content"))
                        .metadata(json.readValue(rs.getString("metadata"), METADATA_TYPE)).build()).list();
    }
}
