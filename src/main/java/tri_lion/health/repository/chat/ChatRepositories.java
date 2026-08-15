package tri_lion.health.repository.chat;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
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
}
