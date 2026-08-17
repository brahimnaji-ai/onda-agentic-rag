package ma.onda.rag.document.infra;

import ma.onda.rag.document.domain.Document;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface DocumentRepository extends JpaRepository<Document, UUID> {

    List<Document> findByUploadedByIdOrderByCreatedAtDesc(UUID userId);

    Optional<Document> findByIdAndUploadedById(UUID id, UUID userId);

    boolean existsByIdAndUploadedById(UUID id, UUID userId);
}
