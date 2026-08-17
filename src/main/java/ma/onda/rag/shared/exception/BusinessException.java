package ma.onda.rag.shared.exception;


import lombok.Getter;

import java.util.Arrays;
import java.util.IllegalFormatException;

@Getter
public class BusinessException extends RuntimeException {
    private final ErrorCode errorCode;
    private final Object[] args;

    public BusinessException(final ErrorCode errorCode, final Object... args) {
        super(getFormatted(errorCode, args));
        this.errorCode = errorCode;
        this.args = args;
    }

    private static String getFormatted(ErrorCode errorCode, Object[] args) {
        if (args != null && args.length > 0) {
            try {
                return String.format(errorCode.getDefaultMessage(), args);
            } catch (IllegalFormatException ex) {
                return errorCode.getDefaultMessage() + " " + Arrays.toString(args);
            }
        }
        return errorCode.getDefaultMessage();
    }
}