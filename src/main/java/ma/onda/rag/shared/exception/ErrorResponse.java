package ma.onda.rag.shared.exception;

import java.time.LocalDateTime;
import java.util.List;

public record ErrorResponse(
        LocalDateTime timestamp,
        int status,
        String error,
        String message,
        List<ValidationError> validationErrors
) {

    public record ValidationError(String field, String message) {
    }

    public static ErrorResponse of(int status, String error, String message) {
        return new ErrorResponse(LocalDateTime.now(), status, error, message, List.of());
    }

    public static ErrorResponse of(int status, String error, String message, List<ValidationError> validationErrors) {
        return new ErrorResponse(LocalDateTime.now(), status, error, message, validationErrors);
    }
}