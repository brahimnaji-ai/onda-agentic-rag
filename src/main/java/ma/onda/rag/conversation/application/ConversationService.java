package ma.onda.rag.conversation.application;

import ma.onda.rag.conversation.api.dto.*;
import ma.onda.rag.conversation.domain.ChatMessage;
import ma.onda.rag.conversation.infra.persistance.*;
import ma.onda.rag.conversation.domain.Conversation;
import ma.onda.rag.conversation.domain.MessageType;
import ma.onda.rag.identity.infra.keycloak.SecurityUserContext;
import ma.onda.rag.shared.exception.BusinessException;
import ma.onda.rag.shared.exception.ErrorCode;
import ma.onda.rag.user.domain.User;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/** Application service for user-isolated conversation history. */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ConversationService {

    static final String DEFAULT_TITLE = "New conversation";

    private final ConversationRepository conversationRepository;
    private final ChatMessageRepository chatMessageRepository;
    private final SecurityUserContext securityUserContext;

    @Transactional
    public ConversationResponse createConversation(String title) {
        User currentUser = securityUserContext.getCurrentUser();
        Conversation conversation = conversationRepository.save(Conversation.builder()
                .user(currentUser)
                .title(normalizeTitle(title))
                .build());
        return toResponse(conversation);
    }

    public List<ConversationResponse> getUserConversations() {
        User currentUser = securityUserContext.getCurrentUser();
        return conversationRepository.findByUserIdOrderByCreatedAtDesc(currentUser.getId()).stream()
                .map(this::toResponse)
                .toList();
    }

    public ConversationDetailResponse getConversationDetails(UUID conversationId) {
        Conversation conversation = findOwnedConversation(conversationId, securityUserContext.getCurrentUser());
        List<ChatMessageDTO> messages = chatMessageRepository
                .findByConversationIdOrderBySequenceNumberAsc(conversation.getId())
                .stream()
                .map(this::toMessageDto)
                .toList();

        return new ConversationDetailResponse(
                conversation.getId(),
                conversation.getTitle(),
                messages,
                conversation.getCreatedAt());
    }

    /**
     * Persists a message as the next entry in the caller's thread.
     *
     * <p>The locked ownership query prevents both BOLA bypasses and concurrent
     * writers from allocating the same sequence number.
     */
    @Transactional
    public ChatMessage appendMessage(UUID conversationId, MessageType messageType,
                                     String content, String metadata) {
        User currentUser = securityUserContext.getCurrentUser();
        Conversation conversation = conversationRepository
                .findByIdAndUserIdForUpdate(conversationId, currentUser.getId())
                .orElseThrow(() -> new BusinessException(ErrorCode.CONVERSATION_NOT_FOUND, conversationId));

        int nextSequenceNumber = chatMessageRepository
                .findMaxSequenceNumberByConversationId(conversationId)
                .orElse(0) + 1;

        return chatMessageRepository.save(ChatMessage.builder()
                .conversation(conversation)
                .sequenceNumber(nextSequenceNumber)
                .messageType(messageType)
                .content(requireContent(content))
                .metadata(metadata)
                .build());
    }

    @Transactional
    public void deleteConversation(UUID conversationId) {
        Conversation conversation = findOwnedConversation(conversationId, securityUserContext.getCurrentUser());
        conversationRepository.delete(conversation);
    }

    private Conversation findOwnedConversation(UUID conversationId, User currentUser) {
        return conversationRepository.findByIdAndUserId(conversationId, currentUser.getId())
                // Returning 404 keeps ownership information from being disclosed.
                .orElseThrow(() -> new BusinessException(ErrorCode.CONVERSATION_NOT_FOUND, conversationId));
    }

    private String normalizeTitle(String title) {
        if (title == null) {
            return DEFAULT_TITLE;
        }
        String normalized = title.trim();
        if (normalized.isEmpty()) {
            throw new BusinessException(ErrorCode.CONVERSATION_TITLE_BLANK);
        }
        if (normalized.length() > 255) {
            throw new BusinessException(ErrorCode.CONVERSATION_TITLE_TOO_LONG, 255);
        }
        return normalized;
    }

    private String requireContent(String content) {
        if (content == null || content.isBlank()) {
            throw new BusinessException(ErrorCode.MESSAGE_CONTENT_BLANK);
        }
        return content;
    }

    private ConversationResponse toResponse(Conversation conversation) {
        return new ConversationResponse(
                conversation.getId(),
                conversation.getTitle(),
                conversation.getCreatedAt(),
                conversation.getLastModifiedAt());
    }

    private ChatMessageDTO toMessageDto(ChatMessage message) {
        return new ChatMessageDTO(
                message.getId(),
                message.getSequenceNumber(),
                message.getMessageType(),
                message.getContent(),
                message.getMetadata(),
                message.getCreatedAt());
    }
}