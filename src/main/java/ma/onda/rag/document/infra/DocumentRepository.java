package ma.onda.rag.document.infra;

import ma.onda.rag.document.domain.DocumentEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface DocumentRepository extends JpaRepository<DocumentEntity, UUID> {

    List<DocumentEntity> findByUploadedByIdOrderByCreatedAtDesc(UUID userId);

    Optional<DocumentEntity> findByIdAndUploadedById(UUID id, UUID userId);

    boolean existsByIdAndUploadedById(UUID id, UUID userId);
}
