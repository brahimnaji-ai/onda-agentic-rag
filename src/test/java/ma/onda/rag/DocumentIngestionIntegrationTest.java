package ma.onda.rag;

import ma.onda.rag.document.api.dto.DocumentResponse;
import ma.onda.rag.document.domain.DocumentEntity;
import ma.onda.rag.document.domain.DocumentStatus;
import ma.onda.rag.document.infra.DocumentRepository;
import ma.onda.rag.identity.api.dto.response.TokenResponse;
import ma.onda.rag.user.api.UserResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MvcResult;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

public class DocumentIngestionIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private DocumentRepository documentRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    @DisplayName("Should execute multipart upload, vector chunk creation in pgvector, and deletion cleanup")
    void testMultipartUploadVectorChunkCreationAndDeletionCleanup() throws Exception {
        String username = "ingestor_" + System.currentTimeMillis();
        String email = username + "@onda.ma";
        String password = "SecurePassword123!";

        UserResponse user = registerUser(username, email, password, "Doc", "Ingestor");
        grantIngestorRole(user.keycloakId());

        TokenResponse tokens = loginUser(username, password);

        // 1. Multipart file upload
        String contentText = "OFFICE NATIONAL DES AEROPORTS (ONDA)\n" +
                "Security Policy Document 2026.\n" +
                "All airport personnel must display official badges at all security checkpoints.\n" +
                "Access to restricted operational areas requires multi-factor authentication approval.";

        MockMultipartFile file = new MockMultipartFile(
                "file",
                "onda_security_policy.txt",
                MediaType.TEXT_PLAIN_VALUE,
                contentText.getBytes()
        );

        MvcResult uploadResult = mockMvc.perform(multipart("/api/v1/documents")
                        .file(file)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokens.accessToken()))
                .andExpect(status().isCreated())
                .andReturn();

        DocumentResponse docResponse = objectMapper.readValue(uploadResult.getResponse().getContentAsString(), DocumentResponse.class);

        assertThat(docResponse).isNotNull();
        assertThat(docResponse.id()).isNotNull();
        assertThat(docResponse.name()).isEqualTo("onda_security_policy.txt");
        assertThat(docResponse.status()).isEqualTo(DocumentStatus.COMPLETED);

        UUID docId = docResponse.id();

        // 2. Verify PostgreSQL domain entity persistence
        Optional<DocumentEntity> dbDoc = documentRepository.findById(docId);
        assertThat(dbDoc).isPresent();
        assertThat(dbDoc.get().getStatus()).isEqualTo(DocumentStatus.COMPLETED);

        // 3. Verify pgvector vector_store table chunk persistence
        Integer vectorCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM vector_store WHERE metadata->>'document_id' = ?",
                Integer.class,
                docId.toString()
        );
        assertThat(vectorCount).isNotNull().isGreaterThan(0);

        // 4. Get User Documents list
        MvcResult listResult = mockMvc.perform(get("/api/v1/documents")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokens.accessToken()))
                .andExpect(status().isOk())
                .andReturn();

        List<?> userDocs = objectMapper.readValue(listResult.getResponse().getContentAsString(), List.class);
        assertThat(userDocs).isNotEmpty();

        // 5. Deletion Cleanup
        mockMvc.perform(delete("/api/v1/documents/" + docId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokens.accessToken()))
                .andExpect(status().isNoContent());

        // Verify document entity deleted
        assertThat(documentRepository.findById(docId)).isEmpty();

        // Verify vector chunks removed from pgvector vector_store
        Integer vectorCountAfterDelete = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM vector_store WHERE metadata->>'document_id' = ?",
                Integer.class,
                docId.toString()
        );
        assertThat(vectorCountAfterDelete).isEqualTo(0);
    }
}
