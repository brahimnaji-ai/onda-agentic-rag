package ma.onda.rag.identity.application;

import jakarta.ws.rs.core.Response;
import ma.onda.rag.identity.api.dto.request.RegisterRequest;
import ma.onda.rag.identity.infra.keycloak.KeycloakProperties;
import ma.onda.rag.shared.exception.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.keycloak.admin.client.Keycloak;
import org.keycloak.admin.client.resource.RealmResource;
import org.keycloak.admin.client.resource.UsersResource;
import org.keycloak.representations.idm.UserRepresentation;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.client.RestTemplate;

import static ma.onda.rag.shared.exception.ErrorCode.KEYCLOAK_USER_CREATION_FAILED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class KeycloakAdminClientServiceTest {

    private static final String REALM = "onda-rag-realm";

    @Mock
    private Keycloak keycloak;

    @Mock
    private RealmResource realm;

    @Mock
    private UsersResource users;

    @Mock
    private Response response;

    @Mock
    private RestTemplate restTemplate;

    private KeycloakAdminClientService service;

    @BeforeEach
    void setUp() {
        service = new KeycloakAdminClientService(
                new KeycloakProperties("http://localhost:8081", REALM, "onda-rag-api", "test-secret"),
                restTemplate,
                keycloak
        );
        when(keycloak.realm(REALM)).thenReturn(realm);
        when(realm.users()).thenReturn(users);
    }

    @Test
    @DisplayName("Maps a Keycloak authorization rejection to the registration gateway error")
    void createUser_whenServiceAccountIsForbidden_throwsGatewayBusinessException() {
        when(users.create(any(UserRepresentation.class))).thenReturn(response);
        when(response.getStatus()).thenReturn(Response.Status.FORBIDDEN.getStatusCode());
        when(response.hasEntity()).thenReturn(true);
        when(response.readEntity(String.class)).thenReturn("forbidden");

        assertThatThrownBy(() -> service.createUserInKeycloak(registerRequest()))
                .isInstanceOf(BusinessException.class)
                .satisfies(exception -> {
                    BusinessException businessException = (BusinessException) exception;
                    assertThat(businessException.getErrorCode()).isEqualTo(KEYCLOAK_USER_CREATION_FAILED);
                    assertThat(businessException.getMessage()).contains("HTTP 403");
                });
    }

    private RegisterRequest registerRequest() {
        return new RegisterRequest("b_naji", "b.naji@onda.ma", "SecureP@ss1!", "Brahim", "Naji");
    }
}
