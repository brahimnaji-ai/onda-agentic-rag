package ma.onda.rag.conversation.infra.persistance;

import jakarta.persistence.LockModeType;
import ma.onda.rag.conversation.domain.Conversation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface ConversationRepository extends JpaRepository<Conversation, UUID> {

    List<Conversation> findByUserIdOrderByCreatedAtDesc(UUID userId);

    Optional<Conversation> findByIdAndUserId(UUID id, UUID userId);

    boolean existsByIdAndUserId(UUID id, UUID userId);

    /**
     * Locks the owned conversation while a new message number is allocated.
     * This serializes appends for one thread, so MAX(sequence_number) + 1
     * cannot be assigned concurrently to two messages.
     */

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT c FROM Conversation c WHERE c.id = :id AND c.user.id = :userId")
    Optional<Conversation> findByIdAndUserIdForUpdate(
            @Param("id") UUID id,
            @Param("userId") UUID userId);
}
