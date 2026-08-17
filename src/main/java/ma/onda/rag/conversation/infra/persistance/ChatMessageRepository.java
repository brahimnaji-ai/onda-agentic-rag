package ma.onda.rag.conversation.infra.persistance;

import ma.onda.rag.conversation.domain.ChatMessage;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface ChatMessageRepository extends JpaRepository<ChatMessage, UUID> {

    List<ChatMessage> findByConversationIdOrderBySequenceNumberAsc(UUID conversationId);

    @Query("SELECT MAX(m.sequenceNumber) FROM ChatMessage m WHERE m.conversation.id = :conversationId")
    Optional<Integer> findMaxSequenceNumberByConversationId(@Param("conversationId") UUID conversationId);
}
