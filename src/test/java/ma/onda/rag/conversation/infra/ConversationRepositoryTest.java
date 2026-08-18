package ma.onda.rag.conversation.infra;

import ma.onda.rag.conversation.domain.ChatMessage;
import ma.onda.rag.conversation.domain.Conversation;
import ma.onda.rag.conversation.domain.MessageType;
import ma.onda.rag.conversation.infra.persistance.ChatMessageRepository;
import ma.onda.rag.conversation.infra.persistance.ConversationRepository;
import ma.onda.rag.shared.persistance.JpaAuditingConfig;
import ma.onda.rag.user.domain.User;
import ma.onda.rag.user.infra.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@ActiveProfiles("test")
@Import(JpaAuditingConfig.class)
class ConversationRepositoryTest {

    @Autowired private ConversationRepository conversationRepository;
    @Autowired private ChatMessageRepository chatMessageRepository;
    @Autowired private UserRepository userRepository;

    @Test
    void ownershipFinderDoesNotExposeAnotherUsersConversation() {
        User owner = savedUser("owner");
        User otherUser = savedUser("other");
        Conversation conversation = conversationRepository.save(
                Conversation.builder().user(owner).title("Private").build());

        assertThat(conversationRepository.findByIdAndUserId(conversation.getId(), owner.getId())).isPresent();
        assertThat(conversationRepository.findByIdAndUserId(conversation.getId(), otherUser.getId())).isEmpty();
    }

    @Test
    void maxSequenceNumberQueryTracksTheHighestInsertedMessage() {
        User owner = savedUser("owner");
        Conversation conversation = conversationRepository.save(
                Conversation.builder().user(owner).title("Thread").build());
        conversation.getMessages().add(message(conversation, 1, "First"));
        conversation.getMessages().add(message(conversation, 2, "Second"));
        conversationRepository.save(conversation);

        assertThat(chatMessageRepository.findMaxSequenceNumberByConversationId(conversation.getId()))
                .contains(2);
        assertThat(chatMessageRepository.findByConversationIdOrderBySequenceNumberAsc(conversation.getId()))
                .extracting(ChatMessage::getSequenceNumber)
                .containsExactly(1, 2);
    }

    @Test
    void deletingAConversationCascadesToItsMessages() {
        User owner = savedUser("owner");
        Conversation conversation = conversationRepository.save(
                Conversation.builder().user(owner).title("Thread").build());
        conversation.getMessages().add(message(conversation, 1, "First"));
        conversation.getMessages().add(message(conversation, 2, "Second"));
        conversationRepository.save(conversation);

        conversationRepository.delete(conversation);

        assertThat(chatMessageRepository.findByConversationIdOrderBySequenceNumberAsc(conversation.getId()))
                .isEmpty();
    }

    private User savedUser(String suffix) {
        return userRepository.save(User.builder()
                .keycloakId("kc-" + suffix + '-' + UUID.randomUUID())
                .username(suffix + UUID.randomUUID())
                .email(suffix + UUID.randomUUID() + "@example.com")
                .firstName("Test")
                .lastName("User")
                .build());
    }

    private ChatMessage message(Conversation conversation, int sequenceNumber, String content) {
        return ChatMessage.builder()
                .conversation(conversation)
                .sequenceNumber(sequenceNumber)
                .messageType(MessageType.USER)
                .content(content)
                .build();
    }
}