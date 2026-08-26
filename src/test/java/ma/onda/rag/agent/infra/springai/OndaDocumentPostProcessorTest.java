package ma.onda.rag.agent.infra.springai;

import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.rag.Query;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class OndaDocumentPostProcessorTest {

    private final OndaDocumentPostProcessor postProcessor = new OndaDocumentPostProcessor(new PostRetrievalProperties(2));

    @Test
    void removesBlankAndDuplicateFrenchChunksThenAppliesTheContextLimit() {
        Document first = Document.builder().text("Conditions d'accès à l'aérogare").build();
        Document duplicate = Document.builder().text(" Conditions d'accès   à l'aérogare ").build();
        Document second = Document.builder().text("Horaires de l'aéroport Mohammed V").build();
        Document third = Document.builder().text("Services aux passagers").build();
        Document blank = Document.builder().text(" ").build();

        List<Document> processed = postProcessor.process(new Query("aérogare Casablanca"),
                List.of(first, duplicate, second, third, blank));

        assertThat(processed).containsExactly(first, second);
    }
}
