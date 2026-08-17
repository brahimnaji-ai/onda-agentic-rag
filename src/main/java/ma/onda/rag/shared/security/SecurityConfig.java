package ma.onda.rag.shared.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import ma.onda.rag.identity.infra.keycloak.KeycloakJwtAuthenticationConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.server.resource.web.DefaultBearerTokenResolver;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;

import java.net.URI;

@Configuration
@EnableWebSecurity
@EnableMethodSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    private static final String[] PUBLIC_PATHS = {
            "/api/v1/auth/register",
            "/api/v1/auth/login",
            "/api/v1/auth/refresh",
            "/actuator/health",
            "/swagger-ui/**",
            "/v3/api-docs/**"
    };

    private final KeycloakJwtAuthenticationConverter jwtAuthenticationConverter;

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(PUBLIC_PATHS).permitAll()
                        .anyRequest().authenticated()
                )
                .oauth2ResourceServer(oauth2 -> oauth2
                        // Skip JWT decoding entirely for public endpoints.
                        // Without this, BearerTokenAuthenticationFilter runs before permitAll()
                        // and a stale/invalid Bearer header on /register or /login causes a 401.
                        .bearerTokenResolver(request -> {
                            String uri = request.getRequestURI();
                            for (String path : PUBLIC_PATHS) {
                                boolean matches = path.endsWith("/**")
                                        ? uri.startsWith(path.substring(0, path.length() - 3))
                                        : uri.equals(path);
                                if (matches) {
                                    return null; // no token extraction → request is anonymous
                                }
                            }
                            return new DefaultBearerTokenResolver().resolve(request);
                        })
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(jwtAuthenticationConverter))
                        .authenticationEntryPoint(authenticationEntryPoint())
                )
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint(authenticationEntryPoint())
                        .accessDeniedHandler(accessDeniedHandler())
                );

        return http.build();
    }

    @Bean
    public AuthenticationEntryPoint authenticationEntryPoint() {
        return (request, response, authException) -> {
            response.setStatus(HttpStatus.UNAUTHORIZED.value());
            response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);

            ProblemDetail problemDetail = ProblemDetail.forStatusAndDetail(
                    HttpStatus.UNAUTHORIZED,
                    "Full authentication is required to access this resource"
            );
            problemDetail.setTitle("Unauthorized");
            problemDetail.setInstance(URI.create(request.getRequestURI()));

            new ObjectMapper().writeValue(response.getOutputStream(), problemDetail);
        };
    }

    @Bean
    public AccessDeniedHandler accessDeniedHandler() {
        return (request, response, accessDeniedException) -> {
            response.setStatus(HttpStatus.FORBIDDEN.value());
            response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);

            ProblemDetail problemDetail = ProblemDetail.forStatusAndDetail(
                    HttpStatus.FORBIDDEN,
                    "Access is denied"
            );
            problemDetail.setTitle("Forbidden");
            problemDetail.setInstance(URI.create(request.getRequestURI()));

            new ObjectMapper().writeValue(response.getOutputStream(), problemDetail);
        };
    }
}
