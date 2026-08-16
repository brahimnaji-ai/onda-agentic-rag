package ma.onda.rag.shared.exception;


import lombok.Getter;
import org.springframework.http.HttpStatus;

import static org.springframework.http.HttpStatus.*;

@Getter
public enum ErrorCode {
    USER_NOT_REGISTERED("DATABASE", "User not registered in local database", NOT_FOUND),
    JWT_NOT_FOUND("SECURITY", "No authenticated JWT token found in security context", NOT_FOUND),


    ;
    private final String code;
    private final String defaultMessage;
    private final HttpStatus status;

    ErrorCode(String code, String defaultMessage, HttpStatus status) {
        this.code = code;
        this.defaultMessage = defaultMessage;
        this.status = status;
    }
}
