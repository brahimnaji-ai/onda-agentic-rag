package ma.onda.rag.identity.infra.keycloak;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "keycloak")
public record KeycloakProperties(

    /** Base URL of the Keycloak server (no trailing slash). */

    @NotBlank String serverUrl,

    /** Realm name. */

    @NotBlank String realm,

    /** Confidential client ID used for Admin REST calls and token exchange. */

    @NotBlank String clientId,

    /** Client secret. */

    @NotBlank String clientSecret
) {}
