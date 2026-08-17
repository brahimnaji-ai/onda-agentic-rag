package ma.onda.rag.identity.application;

import ma.onda.rag.identity.api.dto.request.RegisterRequest;
import ma.onda.rag.shared.exception.BusinessException;
import ma.onda.rag.user.api.UserResponse;
import ma.onda.rag.user.domain.User;
import ma.onda.rag.user.infra.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;


import java.util.UUID;

import static ma.onda.rag.shared.exception.ErrorCode.KEYCLOAK_USER_CREATION_FAILED;
import static ma.onda.rag.shared.exception.ErrorCode.USER_ALREADY_EXISTS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link RegistrationService}.
 *
 * <p>The Keycloak side is stubbed with a {@link Mock} so that no real HTTP calls
 * are made. The compensation logic (delete Keycloak user on DB failure) is the
 * primary focus.
 */
@ExtendWith(MockitoExtension.class)
class RegistrationServiceTest {

    @Mock
    private KeycloakAdminClientService keycloakAdminClientService;

    @Mock
    private UserRepository userRepository;

    @InjectMocks
    private RegistrationService registrationService;

    private RegisterRequest validRequest;
    private static final String KC_ID = "kc-uuid-abc123";

    @BeforeEach
    void setUp() {
        validRequest = new RegisterRequest(
                "b_naji",
                "b.naji@onda.ma",
                "SecureP@ss1!",
                "Brahim",
                "Naji"
        );
    }

    // -----------------------------------------------------------------------
    // Happy path
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("Should complete registration saga and return UserResponse on success")
    void register_happyPath_returnsUserResponse() {
        // Arrange
        User savedUser = buildUser();
        when(keycloakAdminClientService.createUserInKeycloak(validRequest)).thenReturn(KC_ID);
        when(userRepository.save(any(User.class))).thenReturn(savedUser);

        // Act
        UserResponse response = registrationService.register(validRequest);

        // Assert
        assertThat(response).isNotNull();
        assertThat(response.keycloakId()).isEqualTo(KC_ID);
        assertThat(response.username()).isEqualTo("b_naji");
        assertThat(response.email()).isEqualTo("b.naji@onda.ma");

        verify(keycloakAdminClientService).createUserInKeycloak(validRequest);
        verify(userRepository).save(any(User.class));
        verify(keycloakAdminClientService, never()).deleteUserInKeycloak(any());
    }

    // -----------------------------------------------------------------------
    // Compensation — DataIntegrityViolationException (duplicate user)
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("Should compensate by deleting Keycloak user and throw BusinessException on DB constraint violation")
    void register_whenDbConstraintViolated_compensatesAndThrowsBusinessException() {
        // Arrange
        when(keycloakAdminClientService.createUserInKeycloak(validRequest)).thenReturn(KC_ID);
        when(userRepository.save(any(User.class)))
                .thenThrow(new DataIntegrityViolationException("unique constraint violated"));

        // Act + Assert
        assertThatThrownBy(() -> registrationService.register(validRequest))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> {
                    BusinessException be = (BusinessException) ex;
                    assertThat(be.getErrorCode()).isEqualTo(USER_ALREADY_EXISTS);
                    assertThat(be.getMessage()).contains("b_naji");
                });

        // Verify compensation was triggered
        verify(keycloakAdminClientService).deleteUserInKeycloak(KC_ID);
    }

    // -----------------------------------------------------------------------
    // Compensation — unexpected RuntimeException
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("Should compensate by deleting Keycloak user and rethrow on unexpected DB error")
    void register_whenUnexpectedDbError_compensatesAndRethrows() {
        // Arrange
        RuntimeException unexpected = new RuntimeException("Unexpected DB failure");
        when(keycloakAdminClientService.createUserInKeycloak(validRequest)).thenReturn(KC_ID);
        when(userRepository.save(any(User.class))).thenThrow(unexpected);

        // Act + Assert
        assertThatThrownBy(() -> registrationService.register(validRequest))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("Unexpected DB failure");

        // Verify compensation was still triggered
        verify(keycloakAdminClientService).deleteUserInKeycloak(KC_ID);
    }

    // -----------------------------------------------------------------------
    // Keycloak failure (no compensation needed — local DB untouched)
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("Should propagate BusinessException(KEYCLOAK_USER_CREATION_FAILED) without touching the DB or compensating")
    void register_whenKeycloakFails_propagatesExceptionWithoutDbInteraction() {
        // Arrange
        when(keycloakAdminClientService.createUserInKeycloak(validRequest))
                .thenThrow(new BusinessException(KEYCLOAK_USER_CREATION_FAILED, 409, "conflict"));

        // Act + Assert
        assertThatThrownBy(() -> registrationService.register(validRequest))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> {
                    BusinessException be = (BusinessException) ex;
                    assertThat(be.getErrorCode()).isEqualTo(KEYCLOAK_USER_CREATION_FAILED);
                    assertThat(be.getMessage()).contains("HTTP 409");
                });

        // No DB interaction, no compensation
        verifyNoInteractions(userRepository);
        verify(keycloakAdminClientService, never()).deleteUserInKeycloak(any());
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private User buildUser() {
        return User.builder()
                .id(UUID.randomUUID())
                .keycloakId(KC_ID)
                .username("b_naji")
                .email("b.naji@onda.ma")
                .firstName("Brahim")
                .lastName("Naji")
                .build();
    }
}
