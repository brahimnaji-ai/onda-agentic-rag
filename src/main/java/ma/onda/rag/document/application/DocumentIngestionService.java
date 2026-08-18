package ma.onda.rag.document.application;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import ma.onda.rag.document.api.dto.DocumentResponse;
import ma.onda.rag.document.domain.DocumentEntity;
import ma.onda.rag.document.domain.DocumentStatus;
import ma.onda.rag.document.infra.DocumentRepository;
import ma.onda.rag.document.infra.VectorMetadataKeys;
import ma.onda.rag.identity.infra.keycloak.SecurityUserContext;
import ma.onda.rag.shared.exception.BusinessException;
import ma.onda.rag.shared.exception.ErrorCode;
import ma.onda.rag.user.domain.User;
import org.springframework.ai.document.Document;
import org.springframework.ai.reader.TextReader;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.reader.tika.TikaDocumentReader;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class DocumentIngestionService {

    private static final List<String> ALLOWED_EXTENSIONS = List.of(".pdf", ".txt", ".md");
    private static final List<String> ALLOWED_CONTENT_TYPES = List.of(
            "application/pdf",
            "text/plain",
            "text/markdown",
            "text/x-markdown",
            "application/octet-stream" // for unmapped markdown/text files
    );

    private final DocumentRepository documentRepository;
    private final SecurityUserContext securityUserContext;
    private final VectorStore vectorStore;

    @Transactional
    public DocumentResponse ingestDocument(MultipartFile file) {
        validateFile(file);

        User currentUser = securityUserContext.getCurrentUser();
        String originalFilename = file.getOriginalFilename() != null ? file.getOriginalFilename() : "document";

        DocumentEntity documentEntity = DocumentEntity.builder()
                .name(originalFilename)
                .contentType(file.getContentType() != null ? file.getContentType() : "application/octet-stream")
                .sizeBytes(file.getSize())
                .status(DocumentStatus.PROCESSING)
                .uploadedBy(currentUser)
                .build();

        documentEntity = documentRepository.save(documentEntity);

        try {
            List<Document> rawDocuments = parseFile(file);

            TokenTextSplitter splitter =  TokenTextSplitter.builder()
                    .withChunkSize(600)
                    .withMinChunkSizeChars(300)
                    .withMinChunkLengthToEmbed(100)
                    .withMaxNumChunks(10_000)
                    .withKeepSeparator(true)
                    .build();
            
            List<Document> chunks = splitter.split(rawDocuments);

            for (int i = 0; i < chunks.size(); i++) {
                Document chunk = chunks.get(i);
                chunk.getMetadata().put(VectorMetadataKeys.DOCUMENT_ID, documentEntity.getId().toString());
                chunk.getMetadata().put(VectorMetadataKeys.UPLOADED_BY, currentUser.getId().toString());
                chunk.getMetadata().put(VectorMetadataKeys.SOURCE, documentEntity.getName());
                chunk.getMetadata().put(VectorMetadataKeys.CHUNK_INDEX, i);
            }

            if (!chunks.isEmpty()) {
                vectorStore.add(chunks);
            }

            documentEntity.setStatus(DocumentStatus.COMPLETED);
            documentEntity = documentRepository.save(documentEntity);
            log.info("Successfully ingested document '{}' (ID: {}) into {} chunks", documentEntity.getName(), documentEntity.getId(), chunks.size());

            return DocumentResponse.fromEntity(documentEntity);

        } catch (Exception ex) {
            log.error("Failed to ingest document '{}' (ID: {})", documentEntity.getName(), documentEntity.getId(), ex);
            documentEntity.setStatus(DocumentStatus.FAILED);
            documentRepository.save(documentEntity);
            throw new BusinessException(ErrorCode.DOCUMENT_PROCESSING_FAILED, ex.getMessage());
        }
    }

    @Transactional(readOnly = true)
    public List<DocumentResponse> getUserDocuments() {
        User currentUser = securityUserContext.getCurrentUser();
        return documentRepository.findByUploadedByIdOrderByCreatedAtDesc(currentUser.getId())
                .stream()
                .map(DocumentResponse::fromEntity)
                .toList();
    }

    @Transactional
    public void deleteDocument(UUID documentId) {
        User currentUser = securityUserContext.getCurrentUser();

        DocumentEntity documentEntity = documentRepository.findByIdAndUploadedById(documentId, currentUser.getId())
                .orElseThrow(() -> new BusinessException(ErrorCode.DOCUMENT_NOT_FOUND, documentId));

        try {
            FilterExpressionBuilder b = new FilterExpressionBuilder();
            vectorStore.delete(b.eq(VectorMetadataKeys.DOCUMENT_ID, documentId.toString()).build());
        } catch (Exception ex) {
            log.warn("Failed to remove vector chunks for document ID {}: {}", documentId, ex.getMessage());
        }

        documentRepository.delete(documentEntity);
        log.info("Deleted document '{}' (ID: {}) and its associated vector chunks", documentEntity.getName(), documentId);
    }

    private void validateFile(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new BusinessException(ErrorCode.DOCUMENT_EMPTY_FILE);
        }

        String filename = file.getOriginalFilename() != null ? file.getOriginalFilename().toLowerCase() : "";
        String contentType = file.getContentType() != null ? file.getContentType().toLowerCase() : "";

        boolean validExtension = ALLOWED_EXTENSIONS.stream().anyMatch(filename::endsWith);
        boolean validContentType = ALLOWED_CONTENT_TYPES.stream().anyMatch(contentType::contains);

        if (!validExtension && !validContentType) {
            throw new BusinessException(ErrorCode.DOCUMENT_INVALID_TYPE, filename);
        }
    }

    private List<Document> parseFile(MultipartFile file) throws IOException {
        byte[] bytes = file.getBytes();
        String filename = file.getOriginalFilename() != null ? file.getOriginalFilename() : "document";

        Resource resource = new ByteArrayResource(bytes) {
            @Override
            public String getFilename() {
                return filename;
            }
        };

        try {
            TikaDocumentReader tikaReader = new TikaDocumentReader(resource);
            List<Document> docs = tikaReader.get();
            if (docs != null && !docs.isEmpty()) {
                return docs;
            }
        } catch (Exception ex) {
            log.warn("TikaDocumentReader failed to parse file '{}', falling back to TextReader: {}", filename, ex.getMessage());
        }

        TextReader textReader = new TextReader(resource);
        return textReader.get();
    }
}
