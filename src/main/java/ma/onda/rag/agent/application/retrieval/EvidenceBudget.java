package ma.onda.rag.agent.application.retrieval;

import ma.onda.rag.document.infra.VectorMetadataKeys;
import org.springframework.ai.document.Document;
import tools.jackson.databind.json.JsonMapper;

import java.util.LinkedHashMap;
import java.util.Map;

/** Conservative text-token upper bound: one token per serialized UTF-8 byte, not chars/4.
 * Includes snippet fields, JSON escaping, separators and envelope. Excludes chat history,
 * system prompts and other tools' output.
 */
public final class EvidenceBudget {
    public static final String USED_TOKENS = "retrieval.used-context-tokens";
    public static final String USED_DOCUMENTS = "retrieval.used-documents";
    public static final int ENVELOPE_TOKENS = 16;
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private EvidenceBudget() {}

    public static int tokens(Document document) {
        Map<String, Object> snippet = new LinkedHashMap<>();
        snippet.put("documentId", metadata(document, VectorMetadataKeys.DOCUMENT_ID));
        snippet.put("sourceFilename", metadata(document, VectorMetadataKeys.SOURCE));
        snippet.put("chunkContent", document.getText());
        snippet.put("relevanceScore", document.getScore());
        return Math.addExact(JSON.writeValueAsBytes(snippet).length, 1);
    }

    private static String metadata(Document document, String key) {
        Object value = document.getMetadata().get(key);
        return value == null ? null : value.toString();
    }
}
