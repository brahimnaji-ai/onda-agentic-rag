package ma.onda.rag.identity.infra.keycloak;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Typed binding for the {@code keycloak.*} block in {@code application.yaml}.
 *
 * <pre>
 * keycloak:
 *   server-url: http://localhost:8081
 *   realm:      onda-rag-realm
 *   client-id:  onda-rag-api
 *   client-secret: <secret>
 * </pre>
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "keycloak")
public class KeycloakProperties {

    /** Base URL of the Keycloak server (no trailing slash). */
    private String serverUrl;

    /** Realm name. */
    private String realm;

    /** Confidential client ID used for Admin REST calls and token exchange. */
    private String clientId;

    /** Client secret. */
    private String clientSecret;
}
