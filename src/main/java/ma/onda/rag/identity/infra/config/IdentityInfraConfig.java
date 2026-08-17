package ma.onda.rag.identity.infra.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestTemplate;

/**
 * Infrastructure beans required by {@link ma.onda.rag.identity.application.KeycloakAdminClientService}
 * for HTTP calls to the Keycloak token and logout endpoints.
 */
@Configuration
public class IdentityInfraConfig {

    /**
     * General-purpose {@link RestTemplate} used to POST to Keycloak's OIDC
     * token / logout endpoints.  No authentication header is needed here since
     * the request body carries {@code client_id} and {@code client_secret}.
     */
    @Bean
    public RestTemplate restTemplate() {
        return new RestTemplate();
    }
}
