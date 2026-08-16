package tri_lion.health.domain.chat;

import jakarta.persistence.*;
import java.time.Instant;
import lombok.*;

@Entity
@Table(name = "chat_messages")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ChatMessage {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "chat_message_id")
    private Long id;

    @Column(name = "chat_conversation_id", nullable = false)
    private Long conversationId;

    @Enumerated(EnumType.STRING)
    @Column(name = "sender_role", nullable = false, length = 20)
    private SenderRole senderRole;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String content;

    @Column(name = "response_type", length = 30)
    private String responseType;

    @Column(name = "pending_ai_action_id")
    private Long pendingActionId;

    @Column(name = "has_image", nullable = false)
    private boolean hasImage;

    @Column(name = "image_object_key", length = 500)
    private String imageObjectKey;

    @Column(name = "image_content_type", length = 100)
    private String imageContentType;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    public ChatMessage(
            Long conversationId,
            SenderRole senderRole,
            String content,
            String responseType,
            Long pendingActionId,
            boolean hasImage,
            String imageObjectKey,
            String imageContentType) {
        this.conversationId = conversationId;
        this.senderRole = senderRole;
        this.content = content;
        this.responseType = responseType;
        this.pendingActionId = pendingActionId;
        this.hasImage = hasImage;
        this.imageObjectKey = imageObjectKey;
        this.imageContentType = imageContentType;
        this.createdAt = Instant.now();
    }

    public enum SenderRole {
        USER,
        ASSISTANT
    }
}
