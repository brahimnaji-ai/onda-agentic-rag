package ma.onda.rag.shared.exception;

public class ResourceNotFoundException extends RuntimeException {
    public ResourceNotFoundException(String resourceName, String keycloakId) {
        super(String.format("%s not found with keycloakId '%s'", resourceName, keycloakId));
    }
}
