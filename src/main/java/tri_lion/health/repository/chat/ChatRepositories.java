package tri_lion.health.repository.chat;

import jakarta.persistence.LockModeType;
import java.util.*;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import tri_lion.health.domain.chat.ChatConversation;
import tri_lion.health.domain.chat.ChatMessage;
import tri_lion.health.domain.chat.PendingAiAction;

public final class ChatRepositories {
    private ChatRepositories() {}

    public interface PendingActions extends JpaRepository<PendingAiAction, Long> {
        @Lock(LockModeType.PESSIMISTIC_WRITE)
        @Query(
                "select action from PendingAiAction action "
                        + "where action.id=:id and action.userId=:userId")
        Optional<PendingAiAction> findOwnedForUpdate(
                @Param("id") Long id, @Param("userId") Long userId);
    }

    public interface Conversations extends JpaRepository<ChatConversation, Long> {
        Optional<ChatConversation> findByIdAndUserIdAndDeletedAtIsNull(Long id, Long userId);

        Optional<ChatConversation> findFirstByUserIdAndDeletedAtIsNullOrderByLastMessageAtDesc(
                Long userId);

        List<ChatConversation> findByUserIdAndDeletedAtIsNullOrderByLastMessageAtDesc(Long userId);
    }

    public interface Messages extends JpaRepository<ChatMessage, Long> {
        Optional<ChatMessage> findFirstByPendingActionIdOrderByIdDesc(Long pendingActionId);

        List<ChatMessage> findByConversationIdOrderByIdDesc(Long conversationId, Pageable pageable);

        List<ChatMessage> findByConversationIdAndIdLessThanOrderByIdDesc(
                Long conversationId, Long beforeMessageId, Pageable pageable);
    }
}
