package ma.onda.rag.conversation.domain;

import ma.onda.rag.shared.persistance.BaseEntity;
import jakarta.persistence.*;
import lombok.*;

import java.util.UUID;

@Entity
@Table(
        name = "chat_messages",
        indexes = @Index(name = "idx_chat_messages_conversation_id", columnList = "conversation_id"),
        uniqueConstraints = @UniqueConstraint(
                name = "uq_chat_messages_conversation_sequence",
                columnNames = {"conversation_id", "sequence_number"}
        )
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ChatMessage extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "conversation_id", nullable = false)
    private Conversation conversation;

    @Column(name = "sequence_number", nullable = false)
    private int sequenceNumber;

    @Enumerated(EnumType.STRING)
    @Column(name = "message_type", nullable = false)
    private MessageType messageType;

    @Column(name = "content", columnDefinition = "TEXT", nullable = false)
    private String content;

    @Column(name = "metadata", columnDefinition = "jsonb")
    private String metadata;
}

