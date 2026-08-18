package ma.onda.rag.conversation.application;

import ma.onda.rag.conversation.domain.ChatMessage;
import ma.onda.rag.conversation.infra.persistance.*;
import ma.onda.rag.conversation.domain.Conversation;
import ma.onda.rag.conversation.domain.MessageType;
import ma.onda.rag.identity.infra.keycloak.SecurityUserContext;
import ma.onda.rag.shared.exception.BusinessException;
import ma.onda.rag.shared.exception.ErrorCode;
import ma.onda.rag.user.domain.User;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ConversationServiceTest {

    @Mock private ConversationRepository conversationRepository;
    @Mock private ChatMessageRepository chatMessageRepository;
    @Mock private SecurityUserContext securityUserContext;
    @InjectMocks private ConversationService conversationService;

    @Test
    void appendMessage_assignsTheNumberAfterTheExistingMaximum() {
        User user = user();
        UUID conversationId = UUID.randomUUID();
        Conversation conversation = Conversation.builder().id(conversationId).user(user).title("Test").build();
        when(securityUserContext.getCurrentUser()).thenReturn(user);
        when(conversationRepository.findByIdAndUserIdForUpdate(conversationId, user.getId()))
                .thenReturn(Optional.of(conversation));
        when(chatMessageRepository.findMaxSequenceNumberByConversationId(conversationId)).thenReturn(Optional.of(2));
        when(chatMessageRepository.save(any(ChatMessage.class))).thenAnswer(invocation -> invocation.getArgument(0));

        ChatMessage saved = conversationService.appendMessage(conversationId, MessageType.USER, "Hello", null);

        assertThat(saved.getSequenceNumber()).isEqualTo(3);
        assertThat(saved.getConversation()).isSameAs(conversation);
        assertThat(saved.getMessageType()).isEqualTo(MessageType.USER);
    }

    @Test
    void appendMessage_rejectsAConversationNotOwnedByTheCaller() {
        User user = user();
        UUID conversationId = UUID.randomUUID();
        when(securityUserContext.getCurrentUser()).thenReturn(user);
        when(conversationRepository.findByIdAndUserIdForUpdate(eq(conversationId), eq(user.getId())))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> conversationService.appendMessage(
                conversationId, MessageType.USER, "Hello", null))
                .isInstanceOf(BusinessException.class)
                .satisfies(exception -> assertThat(((BusinessException) exception).getErrorCode())
                        .isEqualTo(ErrorCode.CONVERSATION_NOT_FOUND));
    }

    @Test
    void deleteConversation_usesTheOwnershipFilteredFinderBeforeDeleting() {
        User user = user();
        UUID conversationId = UUID.randomUUID();
        Conversation conversation = Conversation.builder().id(conversationId).user(user).title("Test").build();
        when(securityUserContext.getCurrentUser()).thenReturn(user);
        when(conversationRepository.findByIdAndUserId(conversationId, user.getId()))
                .thenReturn(Optional.of(conversation));

        conversationService.deleteConversation(conversationId);

        verify(conversationRepository).delete(conversation);
    }

    private User user() {
        User user = User.builder().keycloakId("kc-sub").username("user")
                .email("user@example.com").firstName("Test").lastName("User").build();
        user.setId(UUID.randomUUID());
        return user;
    }
}
