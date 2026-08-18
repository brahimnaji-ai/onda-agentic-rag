package ma.onda.rag.document.api;

import ma.onda.rag.document.api.dto.DocumentResponse;
import ma.onda.rag.document.application.DocumentIngestionService;
import ma.onda.rag.document.domain.DocumentStatus;
import ma.onda.rag.identity.infra.keycloak.KeycloakJwtAuthenticationConverter;
import ma.onda.rag.shared.exception.BusinessException;
import ma.onda.rag.shared.exception.ErrorCode;
import ma.onda.rag.shared.handler.GlobalExceptionHandler;
import ma.onda.rag.shared.security.SecurityConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.annotation.AnnotatedBeanDefinitionReader;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.mock.web.MockServletContext;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.support.GenericWebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@ExtendWith(MockitoExtension.class)
class DocumentControllerTest {

    private MockMvc mockMvc;

    @Mock
    private DocumentIngestionService documentIngestionService;

    @Mock
    private JwtDecoder jwtDecoder;

    @Configuration
    @EnableWebMvc
    static class TestMvcConfig {}

    @BeforeEach
    void setUp() {
        GenericWebApplicationContext context = new GenericWebApplicationContext();
        context.setServletContext(new MockServletContext());
        AnnotatedBeanDefinitionReader reader = new AnnotatedBeanDefinitionReader(context);
        reader.register(TestMvcConfig.class, SecurityConfig.class, KeycloakJwtAuthenticationConverter.class, DocumentController.class, GlobalExceptionHandler.class);
        context.registerBean(DocumentIngestionService.class, () -> documentIngestionService);
        context.registerBean(JwtDecoder.class, () -> jwtDecoder);
        context.refresh();

        mockMvc = MockMvcBuilders.webAppContextSetup(context)
                .apply(SecurityMockMvcConfigurers.springSecurity())
                .build();
    }

    @Test
    @DisplayName("POST /api/v1/documents - Without INGESTOR role should return HTTP 403 Forbidden")
    void uploadDocument_withoutIngestorRole_shouldReturn403() throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", "doc.pdf", "application/pdf", "dummy pdf content".getBytes());

        mockMvc.perform(multipart("/api/v1/documents")
                        .file(file)
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_USER"))))
                .andExpect(status().isForbidden());

        verifyNoInteractions(documentIngestionService);
    }

    @Test
    @DisplayName("POST /api/v1/documents - With INGESTOR role should ingest document and return 201 Created")
    void uploadDocument_withIngestorRole_shouldReturn201() throws Exception {
        UUID docId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        DocumentResponse response = new DocumentResponse(
                docId,
                "doc.pdf",
                "application/pdf",
                1024L,
                DocumentStatus.COMPLETED,
                userId,
                LocalDateTime.now(),
                LocalDateTime.now()
        );

        when(documentIngestionService.ingestDocument(any())).thenReturn(response);

        MockMultipartFile file = new MockMultipartFile("file", "doc.pdf", "application/pdf", "dummy pdf content".getBytes());

        mockMvc.perform(multipart("/api/v1/documents")
                        .file(file)
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_INGESTOR"))))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "http://localhost/api/v1/documents/" + docId))
                .andExpect(jsonPath("$.id").value(docId.toString()))
                .andExpect(jsonPath("$.name").value("doc.pdf"))
                .andExpect(jsonPath("$.status").value("COMPLETED"));
    }

    @Test
    @DisplayName("GET /api/v1/documents - With INGESTOR role should return user documents")
    void getUserDocuments_withIngestorRole_shouldReturn200() throws Exception {
        UUID docId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        DocumentResponse response = new DocumentResponse(
                docId,
                "doc.txt",
                "text/plain",
                512L,
                DocumentStatus.COMPLETED,
                userId,
                LocalDateTime.now(),
                LocalDateTime.now()
        );

        when(documentIngestionService.getUserDocuments()).thenReturn(List.of(response));

        mockMvc.perform(get("/api/v1/documents")
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_INGESTOR"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(docId.toString()))
                .andExpect(jsonPath("$[0].name").value("doc.txt"));
    }

    @Test
    @DisplayName("DELETE /api/v1/documents/{id} - With INGESTOR role should return 204 No Content")
    void deleteDocument_withIngestorRole_shouldReturn204() throws Exception {
        UUID docId = UUID.randomUUID();
        doNothing().when(documentIngestionService).deleteDocument(docId);

        mockMvc.perform(delete("/api/v1/documents/" + docId)
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_INGESTOR"))))
                .andExpect(status().isNoContent());

        verify(documentIngestionService, times(1)).deleteDocument(docId);
    }

    @Test
    @DisplayName("POST /api/v1/documents - Empty file should return HTTP 400 with ProblemDetail")
    void uploadDocument_emptyFile_shouldReturn400ProblemDetail() throws Exception {
        when(documentIngestionService.ingestDocument(any())).thenThrow(new BusinessException(ErrorCode.DOCUMENT_EMPTY_FILE));

        MockMultipartFile emptyFile = new MockMultipartFile("file", "empty.txt", "text/plain", new byte[0]);

        mockMvc.perform(multipart("/api/v1/documents")
                        .file(emptyFile)
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_INGESTOR"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("DOCUMENT_EMPTY_FILE"))
                .andExpect(jsonPath("$.message").value("Uploaded document file must not be empty"));
    }
}
