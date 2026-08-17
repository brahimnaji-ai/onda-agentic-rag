package ma.onda.rag.identity.application;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import ma.onda.rag.identity.api.dto.request.RegisterRequest;
import ma.onda.rag.shared.exception.BusinessException;
import ma.onda.rag.user.api.UserResponse;
import ma.onda.rag.user.domain.User;
import ma.onda.rag.user.infra.UserRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import static ma.onda.rag.shared.exception.ErrorCode.USER_ALREADY_EXISTS;

/**
 * Orchestrates the dual-write registration saga.
 *
 * <pre>
 * Step 1  — Create user in Keycloak   → obtain keycloakId
 * Step 2  — Persist user in PostgreSQL
 * Step 3  — If DB save fails          → compensate: delete Keycloak user
 * </pre>
 *
 * <p>The {@code @Transactional} boundary covers the DB write only; Keycloak is an
 * external system and does not participate in the JPA transaction. Compensation
 * ({@code deleteUserInKeycloak}) is triggered in the {@code catch} block before
 * the exception is re-thrown, ensuring no orphan Keycloak users are left behind.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RegistrationService {

    private final KeycloakAdminClientService keycloakAdminClientService;
    private final UserRepository userRepository;

    /**
     * Executes the dual-write registration saga.
     *
     * @param request the validated registration payload
     * @return a {@link UserResponse} representing the newly created local user
     * @throws ma.onda.rag.shared.exception.BusinessException if Keycloak rejects the creation or the username/e-mail is already in use
     */
    @Transactional
    public UserResponse register(RegisterRequest request) {
        log.info("Starting registration saga for username='{}'", request.username());

        // Step 1 — Create in Keycloak (outside the JPA transaction)
        String keycloakId = keycloakAdminClientService.createUserInKeycloak(request);

        try {
            // Step 2 — Persist in PostgreSQL (inside the JPA transaction)
            User user = User.builder()
                    .keycloakId(keycloakId)
                    .username(request.username())
                    .email(request.email())
                    .firstName(request.firstName())
                    .lastName(request.lastName())
                    .build();

            User saved = userRepository.save(user);
            log.info("Registration saga completed: userId='{}', keycloakId='{}'",
                    saved.getId(), keycloakId);

            return toResponse(saved);

        } catch (DataIntegrityViolationException ex) {
            // Step 3 — Compensate: delete the orphan Keycloak user
            log.error("DB constraint violation during registration for username='{}'. " +
                    "Compensating by deleting Keycloak user '{}'.", request.username(), keycloakId);
            keycloakAdminClientService.deleteUserInKeycloak(keycloakId);
            throw new BusinessException(USER_ALREADY_EXISTS, request.username());
        } catch (Exception ex) {
            // Step 3 (fallback) — Compensate on any unexpected DB error
            log.error("Unexpected error during DB save for username='{}'. " +
                    "Compensating: deleting Keycloak user '{}'.", request.username(), keycloakId, ex);
            keycloakAdminClientService.deleteUserInKeycloak(keycloakId);
            throw ex;
        }
    }

    // Helpers

    private UserResponse toResponse(User user) {
        return new UserResponse(
                user.getId(),
                user.getKeycloakId(),
                user.getUsername(),
                user.getEmail(),
                user.getFirstName(),
                user.getLastName(),
                user.getCreatedAt()
        );
    }
}
