package tri_lion.health.domain.chat;

import jakarta.persistence.*;
import java.time.Instant;
import lombok.*;

@Entity
@Table(name = "chat_conversations")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ChatConversation {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "chat_conversation_id")
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(nullable = false, length = 100)
    private String title;

    @Column(name = "last_message_at", nullable = false)
    private Instant lastMessageAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "deleted_at")
    private Instant deletedAt;

    public ChatConversation(Long userId) {
        Instant now = Instant.now();
        this.userId = userId;
        this.title = "새 대화";
        this.lastMessageAt = now;
        this.createdAt = now;
        this.updatedAt = now;
    }

    public void addMessage(String firstUserMessage) {
        Instant now = Instant.now();
        this.lastMessageAt = now;
        this.updatedAt = now;
        if ("새 대화".equals(title) && firstUserMessage != null && !firstUserMessage.isBlank()) {
            String normalized = firstUserMessage.strip().replaceAll("\\s+", " ");
            this.title = normalized.length() <= 40 ? normalized : normalized.substring(0, 40) + "…";
        }
    }

    public void delete() {
        this.deletedAt = Instant.now();
        this.updatedAt = deletedAt;
    }
}
