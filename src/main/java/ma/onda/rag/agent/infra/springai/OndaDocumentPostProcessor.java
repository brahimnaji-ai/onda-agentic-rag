package ma.onda.rag.agent.infra.springai;

import org.springframework.ai.document.Document;
import org.springframework.ai.rag.Query;
import org.springframework.ai.rag.postretrieval.document.DocumentPostProcessor;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Component("ondaDocumentPostProcessor")
@EnableConfigurationProperties(PostRetrievalProperties.class)
public class OndaDocumentPostProcessor implements DocumentPostProcessor {

    private final PostRetrievalProperties properties;

    public OndaDocumentPostProcessor(PostRetrievalProperties properties) {
        this.properties = properties;
    }

    @Override
    public List<Document> process(Query query, List<Document> documents) {
        if (documents == null || documents.isEmpty()) {
            return List.of();
        }

        Map<String, Document> uniqueDocuments = new LinkedHashMap<>();
        for (Document document : documents) {
            if (document == null || !StringUtils.hasText(document.getText())) {
                continue;
            }
            uniqueDocuments.putIfAbsent(normalize(document.getText()), document);
        }

        return uniqueDocuments.values().stream()
                .limit(properties.maxDocuments())
                .toList();
    }

    private String normalize(String text) {
        return text.replaceAll("\\s+", " ").trim().toLowerCase(Locale.ROOT);
    }
}
