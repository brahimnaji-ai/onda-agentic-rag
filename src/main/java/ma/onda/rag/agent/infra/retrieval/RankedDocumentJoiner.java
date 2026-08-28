package ma.onda.rag.agent.infra.retrieval;

import org.springframework.ai.document.Document;
import org.springframework.ai.rag.Query;
import org.springframework.ai.rag.retrieval.join.DocumentJoiner;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Joins scores of the same type, retaining the best occurrence and stable chunk-ID ties. */
public final class RankedDocumentJoiner implements DocumentJoiner {
    @Override
    public List<Document> join(Map<Query, List<List<Document>>> documentsForQuery) {
        Map<String, Document> unique = new LinkedHashMap<>();
        documentsForQuery.values().stream().flatMap(List::stream).flatMap(List::stream)
                .forEach(document -> unique.merge(document.getId(), document,
                        (first, next) -> score(next) > score(first) ? next : first));
        return unique.values().stream().sorted(Comparator.comparingDouble(RankedDocumentJoiner::score)
                .reversed().thenComparing(Document::getId)).toList();
    }

    private static double score(Document document) {
        return document.getScore() == null ? 0 : document.getScore();
    }
}
