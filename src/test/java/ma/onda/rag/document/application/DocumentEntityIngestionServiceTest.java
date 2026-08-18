package ma.onda.rag.document.application;

import ma.onda.rag.document.api.dto.DocumentResponse;
import ma.onda.rag.document.domain.DocumentEntity;
import ma.onda.rag.document.domain.DocumentStatus;
import ma.onda.rag.document.infra.DocumentRepository;
import ma.onda.rag.document.infra.VectorMetadataKeys;
import ma.onda.rag.identity.infra.keycloak.SecurityUserContext;
import ma.onda.rag.shared.exception.BusinessException;
import ma.onda.rag.shared.exception.ErrorCode;
import ma.onda.rag.user.domain.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.Filter;
import org.springframework.mock.web.MockMultipartFile;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class DocumentEntityIngestionServiceTest {

    @Mock
    private DocumentRepository documentRepository;

    @Mock
    private SecurityUserContext securityUserContext;

    @Mock
    private VectorStore vectorStore;

    @InjectMocks
    private DocumentIngestionService documentIngestionService;

    private User mockUser;
    private UUID userId;
    private UUID documentId;

    @BeforeEach
    void setUp() {
        userId = UUID.randomUUID();
        documentId = UUID.randomUUID();

        mockUser = User.builder()
                .id(userId)
                .username("ingestor_user")
                .email("ingestor@onda.ma")
                .firstName("Ingestor")
                .lastName("User")
                .keycloakId("kc-ingestor-123")
                .build();
    }

    @Test
    @DisplayName("ingestDocument - Should parse, split, add metadata, embed and save document with COMPLETED status")
    void ingestDocument_success() {
        when(securityUserContext.getCurrentUser()).thenReturn(mockUser);

        String longContent = "This is a sufficiently long document that describes the ONDA security policy and its core principles. "
                + "It covers access control, authentication, and the handling of sensitive information across all systems. "
                + "Additional detail is provided about incident response, data retention, and the responsibilities of each team. "
                + "This content is intentionally repeated to ensure the text splitting produces at least one embeddable chunk.";
        MockMultipartFile file = new MockMultipartFile(
                "file",
                "sample.txt",
                "text/plain",
                longContent.getBytes(StandardCharsets.UTF_8)
        );

        DocumentEntity savedProcessingDoc = DocumentEntity.builder()
                .id(documentId)
                .name("sample.txt")
                .contentType("text/plain")
                .sizeBytes(file.getSize())
                .status(DocumentStatus.PROCESSING)
                .uploadedBy(mockUser)
                .build();

        when(documentRepository.save(any(DocumentEntity.class))).thenAnswer(invocation -> {
            DocumentEntity doc = invocation.getArgument(0);
            if (doc.getId() == null) {
                doc.setId(documentId);
            }
            return doc;
        });

        DocumentResponse response = documentIngestionService.ingestDocument(file);

        assertThat(response).isNotNull();
        assertThat(response.id()).isEqualTo(documentId);
        assertThat(response.name()).isEqualTo("sample.txt");
        assertThat(response.status()).isEqualTo(DocumentStatus.COMPLETED);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<org.springframework.ai.document.Document>> chunksCaptor = ArgumentCaptor.forClass(List.class);
        verify(vectorStore, times(1)).add(chunksCaptor.capture());

        List<org.springframework.ai.document.Document> chunks = chunksCaptor.getValue();
        assertThat(chunks).isNotEmpty();
        org.springframework.ai.document.Document firstChunk = chunks.get(0);
        assertThat(firstChunk.getMetadata()).containsEntry(VectorMetadataKeys.DOCUMENT_ID, documentId.toString());
        assertThat(firstChunk.getMetadata()).containsEntry(VectorMetadataKeys.UPLOADED_BY, userId.toString());
        assertThat(firstChunk.getMetadata()).containsEntry(VectorMetadataKeys.SOURCE, "sample.txt");
        assertThat(firstChunk.getMetadata()).containsKey(VectorMetadataKeys.CHUNK_INDEX);
    }

    @Test
    @DisplayName("ingestDocument - Should throw BusinessException when file is empty")
    void ingestDocument_emptyFile_throwsException() {
        MockMultipartFile emptyFile = new MockMultipartFile("file", "empty.txt", "text/plain", new byte[0]);

        assertThatThrownBy(() -> documentIngestionService.ingestDocument(emptyFile))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getErrorCode())
                .isEqualTo(ErrorCode.DOCUMENT_EMPTY_FILE);

        verifyNoInteractions(documentRepository);
        verifyNoInteractions(vectorStore);
    }

    @Test
    @DisplayName("ingestDocument - Should throw BusinessException when content type/extension is invalid")
    void ingestDocument_invalidType_throwsException() {
        MockMultipartFile invalidFile = new MockMultipartFile("file", "script.exe", "application/x-msdownload", "binary".getBytes());

        assertThatThrownBy(() -> documentIngestionService.ingestDocument(invalidFile))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getErrorCode())
                .isEqualTo(ErrorCode.DOCUMENT_INVALID_TYPE);

        verifyNoInteractions(documentRepository);
        verifyNoInteractions(vectorStore);
    }

    @Test
    @DisplayName("getUserDocuments - Should return list of user documents")
    void getUserDocuments_success() {
        when(securityUserContext.getCurrentUser()).thenReturn(mockUser);

        DocumentEntity doc1 = DocumentEntity.builder().id(UUID.randomUUID()).name("doc1.pdf").contentType("application/pdf").sizeBytes(100).status(DocumentStatus.COMPLETED).uploadedBy(mockUser).build();
        DocumentEntity doc2 = DocumentEntity.builder().id(UUID.randomUUID()).name("doc2.md").contentType("text/markdown").sizeBytes(200).status(DocumentStatus.COMPLETED).uploadedBy(mockUser).build();

        when(documentRepository.findByUploadedByIdOrderByCreatedAtDesc(userId)).thenReturn(List.of(doc1, doc2));

        List<DocumentResponse> result = documentIngestionService.getUserDocuments();

        assertThat(result).hasSize(2);
        assertThat(result.get(0).name()).isEqualTo("doc1.pdf");
        assertThat(result.get(1).name()).isEqualTo("doc2.md");
    }

    @Test
    @DisplayName("deleteDocument - Should delete vector chunks and document entity")
    void deleteDocument_success() {
        when(securityUserContext.getCurrentUser()).thenReturn(mockUser);

        DocumentEntity doc = DocumentEntity.builder().id(documentId).name("doc.pdf").contentType("application/pdf").sizeBytes(100).status(DocumentStatus.COMPLETED).uploadedBy(mockUser).build();
        when(documentRepository.findByIdAndUploadedById(documentId, userId)).thenReturn(Optional.of(doc));

        documentIngestionService.deleteDocument(documentId);

        verify(vectorStore, times(1)).delete(any(Filter.Expression.class));
        verify(documentRepository, times(1)).delete(doc);
    }

    @Test
    @DisplayName("deleteDocument - Should throw DOCUMENT_NOT_FOUND when document does not exist for user")
    void deleteDocument_notFound_throwsException() {
        when(securityUserContext.getCurrentUser()).thenReturn(mockUser);
        when(documentRepository.findByIdAndUploadedById(documentId, userId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> documentIngestionService.deleteDocument(documentId))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getErrorCode())
                .isEqualTo(ErrorCode.DOCUMENT_NOT_FOUND);

        verifyNoInteractions(vectorStore);
        verify(documentRepository, never()).delete(any());
    }
}
