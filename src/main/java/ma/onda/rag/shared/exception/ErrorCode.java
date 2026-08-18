package ma.onda.rag.shared.exception;


import lombok.Getter;
import org.springframework.http.HttpStatus;

import static org.springframework.http.HttpStatus.*;

@Getter
public enum ErrorCode {
    USER_NOT_REGISTERED("DATABASE", "User not registered in local database (keycloakId '%s')", NOT_FOUND),
    JWT_NOT_FOUND("SECURITY", "No authenticated JWT token found in security context", NOT_FOUND),
    ACCESS_DENIED("SECURITY", "Access denied for the requested resource", FORBIDDEN),
    USER_ALREADY_EXISTS("DATABASE", "User %s already exists", CONFLICT),
    KEYCLOAK_USER_CREATION_FAILED("KEYCLOAK", "Keycloak rejected user creation (HTTP %d): %s", BAD_GATEWAY),
    KEYCLOAK_TOKEN_RESPONSE_EMPTY("KEYCLOAK", "Empty response from Keycloak token endpoint", BAD_GATEWAY),
    KEYCLOAK_ROLE_NOT_FOUND("KEYCLOAK", "Realm role '%s' not found in Keycloak. Create it in the Keycloak Admin Console.", BAD_GATEWAY),
    CONVERSATION_NOT_FOUND("CONVERSATION", "Conversation with ID '%s' was not found", NOT_FOUND),
    CONVERSATION_TITLE_BLANK("CONVERSATION", "Conversation title must not be blank", BAD_REQUEST),
    CONVERSATION_TITLE_TOO_LONG("CONVERSATION", "Conversation title must not exceed %d characters", BAD_REQUEST),
    MESSAGE_CONTENT_BLANK("CONVERSATION", "Message content must not be blank", BAD_REQUEST);



    private final String code;
    private final String defaultMessage;
    private final HttpStatus status;

    ErrorCode(String code, String defaultMessage, HttpStatus status) {
        this.code = code;
        this.defaultMessage = defaultMessage;
        this.status = status;
    }
}
