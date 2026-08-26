package ma.onda.rag.shared.handler;

import ma.onda.rag.shared.exception.BusinessException;
import ma.onda.rag.shared.exception.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Standalone MockMvc tests for {@link GlobalExceptionHandler}.
 *
 * <p>Each test drives a fake controller endpoint to trigger a specific exception
 * and verifies that the handler produces the correct {@code ErrorResponse}.
 */
class GlobalExceptionHandlerTest {

    private MockMvc mockMvc;

    // -----------------------------------------------------------------------
    // Fake controllers to trigger exceptions
    // -----------------------------------------------------------------------

    @RestController
    @RequestMapping("/test")
    static class FakeController {

        @GetMapping("/user-not-registered")
        public void userNotRegistered() {
            throw new BusinessException(ErrorCode.USER_NOT_REGISTERED, "99");
        }

        @GetMapping("/business-conflict")
        public void businessConflict() {
            throw new BusinessException(ErrorCode.USER_ALREADY_EXISTS, "b_naji");
        }

        @GetMapping("/keycloak-error")
        public void keycloakError() {
            throw new BusinessException(ErrorCode.KEYCLOAK_USER_CREATION_FAILED, 409, "conflict");
        }

        @GetMapping("/document-processing-error")
        public void documentProcessingError() {
            throw new BusinessException(ErrorCode.DOCUMENT_PROCESSING_FAILED,
                    "PreparedStatementCallback; SQL [INSERT INTO public.vector_store ...]");
        }

        record ValidBody(@NotBlank String name) {}

        @PostMapping("/validate")
        public void validate(@RequestBody @Valid ValidBody body) {}
    }

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders
                .standaloneSetup(new FakeController())
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    // -----------------------------------------------------------------------
    // BusinessException (USER_NOT_REGISTERED) → 404
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("BusinessException with USER_NOT_REGISTERED should map to 404 ErrorResponse")
    void userNotRegistered_returns404ErrorResponse() throws Exception {
        mockMvc.perform(get("/test/user-not-registered"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.error").value("USER_NOT_REGISTERED"))
                .andExpect(jsonPath("$.message").value("User not registered in local database (keycloakId '99')"));
    }

    // -----------------------------------------------------------------------
    // BusinessException (USER_ALREADY_EXISTS) → 409
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("BusinessException with USER_ALREADY_EXISTS should map to 409 ErrorResponse")
    void businessException_userAlreadyExists_returns409ErrorResponse() throws Exception {
        mockMvc.perform(get("/test/business-conflict"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.error").value("USER_ALREADY_EXISTS"))
                .andExpect(jsonPath("$.message").value("User b_naji already exists"));
    }

    // -----------------------------------------------------------------------
    // BusinessException (KEYCLOAK_USER_CREATION_FAILED) → 502
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("BusinessException with KEYCLOAK_USER_CREATION_FAILED should map to a safe 502 ErrorResponse")
    void keycloakError_returns502ErrorResponse() throws Exception {
        mockMvc.perform(get("/test/keycloak-error"))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.status").value(502))
                .andExpect(jsonPath("$.error").value("KEYCLOAK_USER_CREATION_FAILED"))
                .andExpect(jsonPath("$.message").value("The request could not be completed. Please try again later."));
    }

    @Test
    @DisplayName("Server errors must not expose SQL or exception details")
    void documentProcessingError_returnsSafeMessage() throws Exception {
        mockMvc.perform(get("/test/document-processing-error"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.error").value("DOCUMENT_PROCESSING_FAILED"))
                .andExpect(jsonPath("$.message").value("The request could not be completed. Please try again later."));
    }

    // -----------------------------------------------------------------------
    // MethodArgumentNotValidException → 400 with nested validation errors
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("Bean Validation failure should map to 400 ErrorResponse with nested field errors")
    void validationException_returns400ErrorResponse() throws Exception {
        mockMvc.perform(post("/test/validate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.error").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.validationErrors[0].field").value("name"))
                .andExpect(jsonPath("$.validationErrors[0].message").value(org.hamcrest.Matchers.containsString("must not be blank")));
    }
}
