package ma.onda.rag.agent.infra.retrieval;

import org.springframework.ai.document.Document;
import org.springframework.ai.rag.Query;

import java.util.List;

@FunctionalInterface
public interface AdjacentChunkRetriever {
    List<Document> retrieve(Query query, Document anchor);
}
