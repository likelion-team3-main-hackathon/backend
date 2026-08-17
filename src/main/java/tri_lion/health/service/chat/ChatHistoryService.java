package tri_lion.health.service.chat;

import static tri_lion.health.dto.chat.ChatDtos.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import tri_lion.health.domain.chat.ChatConversation;
import tri_lion.health.domain.chat.ChatMessage;
import tri_lion.health.exception.ApiException;
import tri_lion.health.external.storage.ObjectStorage;
import tri_lion.health.repository.chat.ChatRepositories;

@Service
public class ChatHistoryService {
    private final ChatRepositories.Conversations conversations;
    private final ChatRepositories.Messages messages;
    private final ObjectStorage storage;
    private final ObjectMapper json;

    public ChatHistoryService(
            ChatRepositories.Conversations conversations,
            ChatRepositories.Messages messages,
            ObjectStorage storage,
            ObjectMapper json) {
        this.conversations = conversations;
        this.messages = messages;
        this.storage = storage;
        this.json = json;
    }

    @Transactional
    public Long resolve(Long userId, Long requestedConversationId) {
        if (requestedConversationId != null) {
            return owned(requestedConversationId, userId).getId();
        }
        return conversations
                .findFirstByUserIdAndDeletedAtIsNullOrderByLastMessageAtDesc(userId)
                .orElseGet(() -> conversations.save(new ChatConversation(userId)))
                .getId();
    }

    @Transactional
    public ConversationSummary create(Long userId) {
        return summary(conversations.save(new ChatConversation(userId)));
    }

    @Transactional(readOnly = true)
    public List<ConversationSummary> list(Long userId) {
        return conversations.findByUserIdAndDeletedAtIsNullOrderByLastMessageAtDesc(userId).stream()
                .map(this::summary)
                .toList();
    }

    @Transactional(readOnly = true)
    public MessagePage messages(
            Long userId, Long conversationId, Long beforeMessageId, int requestedLimit) {
        ChatConversation conversation = owned(conversationId, userId);
        int limit = Math.max(1, Math.min(requestedLimit, 100));
        PageRequest page = PageRequest.of(0, limit + 1);
        List<ChatMessage> descending =
                beforeMessageId == null
                        ? messages.findByConversationIdOrderByIdDesc(conversationId, page)
                        : messages.findByConversationIdAndIdLessThanOrderByIdDesc(
                                conversationId, beforeMessageId, page);
        boolean hasMore = descending.size() > limit;
        if (hasMore) descending = new ArrayList<>(descending.subList(0, limit));
        Long nextBeforeMessageId =
                hasMore && !descending.isEmpty() ? descending.getLast().getId() : null;
        List<MessageResponse> chronological =
                descending.reversed().stream().map(this::response).toList();
        return new MessagePage(
                conversation.getId(),
                conversation.getTitle(),
                chronological,
                nextBeforeMessageId,
                hasMore);
    }

    @Transactional
    public void delete(Long userId, Long conversationId) {
        owned(conversationId, userId).delete();
    }

    @Transactional(readOnly = true)
    public String aiHistory(Long userId, Long conversationId, int maxMessages) {
        owned(conversationId, userId);
        List<ChatHistoryMessage> history =
                messages
                        .findByConversationIdOrderByIdDesc(
                                conversationId, PageRequest.of(0, Math.max(1, maxMessages)))
                        .reversed()
                        .stream()
                        .map(
                                message ->
                                        new ChatHistoryMessage(
                                                message.getSenderRole()
                                                                == ChatMessage.SenderRole.USER
                                                        ? "user"
                                                        : "model",
                                                message.getContent()))
                        .toList();
        try {
            return json.writeValueAsString(history);
        } catch (Exception exception) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "저장된 대화 내역을 변환하지 못했습니다.");
        }
    }

    @Transactional
    public Long saveUserMessage(
            Long userId, Long conversationId, String content, MultipartFile image) {
        ChatConversation conversation = owned(conversationId, userId);
        boolean hasImage = image != null && !image.isEmpty();
        String imageObjectKey = null;
        String imageContentType = null;
        if (hasImage) {
            try {
                imageObjectKey = "chat/" + userId + "/" + conversationId + "/" + UUID.randomUUID();
                imageContentType = image.getContentType();
                storage.put(imageObjectKey, image.getBytes(), imageContentType);
            } catch (Exception exception) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "대화 이미지를 저장하지 못했습니다.");
            }
        }
        ChatMessage saved =
                messages.save(
                        new ChatMessage(
                                conversationId,
                                ChatMessage.SenderRole.USER,
                                content,
                                null,
                                null,
                                hasImage,
                                imageObjectKey,
                                imageContentType));
        conversation.addMessage(content);
        return saved.getId();
    }

    @Transactional
    public void saveAssistantMessage(
            Long userId,
            Long conversationId,
            String content,
            String responseType,
            Long pendingActionId) {
        ChatConversation conversation = owned(conversationId, userId);
        messages.save(
                new ChatMessage(
                        conversationId,
                        ChatMessage.SenderRole.ASSISTANT,
                        content,
                        responseType,
                        pendingActionId,
                        false,
                        null,
                        null));
        conversation.addMessage(null);
    }

    @Transactional
    public void saveActionResult(
            Long userId, Long pendingActionId, String content, String responseType) {
        messages.findFirstByPendingActionIdOrderByIdDesc(pendingActionId)
                .ifPresent(
                        proposal -> {
                            ChatConversation conversation =
                                    owned(proposal.getConversationId(), userId);
                            messages.save(
                                    new ChatMessage(
                                            conversation.getId(),
                                            ChatMessage.SenderRole.ASSISTANT,
                                            content,
                                            responseType,
                                            pendingActionId,
                                            false,
                                            null,
                                            null));
                            conversation.addMessage(null);
                        });
    }

    @Transactional(readOnly = true)
    public ChatImage image(Long userId, Long messageId) {
        ChatMessage message =
                messages.findById(messageId)
                        .orElseThrow(() -> ApiException.notFound("대화 이미지를 찾을 수 없습니다."));
        owned(message.getConversationId(), userId);
        if (!message.isHasImage()
                || message.getImageObjectKey() == null
                || message.getImageObjectKey().isBlank()) {
            throw ApiException.notFound("대화 이미지를 찾을 수 없습니다.");
        }
        return new ChatImage(
                storage.get(message.getImageObjectKey()), message.getImageContentType());
    }

    private ChatConversation owned(Long conversationId, Long userId) {
        return conversations
                .findByIdAndUserIdAndDeletedAtIsNull(conversationId, userId)
                .orElseThrow(() -> ApiException.notFound("대화방을 찾을 수 없습니다."));
    }

    private ConversationSummary summary(ChatConversation conversation) {
        return new ConversationSummary(
                conversation.getId(),
                conversation.getTitle(),
                conversation.getLastMessageAt(),
                conversation.getCreatedAt(),
                conversation.getUpdatedAt());
    }

    private MessageResponse response(ChatMessage message) {
        return new MessageResponse(
                message.getId(),
                message.getConversationId(),
                message.getSenderRole().name(),
                message.getContent(),
                message.getResponseType(),
                message.getPendingActionId(),
                message.isHasImage(),
                message.isHasImage() ? "/api/v1/chat/messages/" + message.getId() + "/image" : null,
                message.getCreatedAt());
    }
}
