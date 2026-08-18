package ma.onda.rag.document.api;

import lombok.RequiredArgsConstructor;
import ma.onda.rag.document.api.dto.DocumentResponse;
import ma.onda.rag.document.application.DocumentIngestionService;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.net.URI;
import java.util.List;
import java.util.UUID;

/**
 * Document ingestion and management endpoints for authorized callers with the {@code INGESTOR} role.
 *
 * <table border="1">
 *   <tr><th>Method</th><th>Path</th><th>Auth</th><th>Returns</th></tr>
 *   <tr><td>POST</td><td>/api/v1/documents</td><td>ROLE_INGESTOR</td><td>201 DocumentResponse</td></tr>
 *   <tr><td>GET</td><td>/api/v1/documents</td><td>ROLE_INGESTOR</td><td>200 List&lt;DocumentResponse&gt;</td></tr>
 *   <tr><td>DELETE</td><td>/api/v1/documents/{id}</td><td>ROLE_INGESTOR</td><td>204</td></tr>
 * </table>
 */
@RestController
@RequestMapping("/api/v1/documents")
@PreAuthorize("hasRole('INGESTOR')")
@RequiredArgsConstructor
public class DocumentController {

    private final DocumentIngestionService documentIngestionService;

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<DocumentResponse> uploadDocument(
            @RequestParam("file") MultipartFile file
    ) {
        DocumentResponse response = documentIngestionService.ingestDocument(file);
        URI location = ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{id}")
                .buildAndExpand(response.id())
                .toUri();
        return ResponseEntity.created(location).body(response);
    }

    @GetMapping
    public ResponseEntity<List<DocumentResponse>> getUserDocuments() {
        return ResponseEntity.ok(documentIngestionService.getUserDocuments());
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteDocument(
            @PathVariable("id") UUID id
    ) {
        documentIngestionService.deleteDocument(id);
        return ResponseEntity.noContent().build();
    }
}
