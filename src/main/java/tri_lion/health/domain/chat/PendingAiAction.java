package tri_lion.health.domain.chat;

import jakarta.persistence.*;
import java.time.Instant;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "pending_ai_actions")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PendingAiAction {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "pending_ai_action_id")
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "method_name", nullable = false, length = 100)
    private String methodName;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "arguments_json", nullable = false)
    private String argumentsJson;

    @Column(name = "proposal_message", nullable = false, columnDefinition = "TEXT")
    private String proposalMessage;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Status status;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "processed_at")
    private Instant processedAt;

    @Version private long version;

    public PendingAiAction(
            Long userId, String methodName, String argumentsJson, String proposalMessage) {
        this.userId = userId;
        this.methodName = methodName;
        this.argumentsJson = argumentsJson;
        this.proposalMessage = proposalMessage;
        this.status = Status.PENDING;
        this.createdAt = Instant.now();
        this.expiresAt = createdAt.plusSeconds(30 * 60);
    }

    public boolean isExpired() {
        return Instant.now().isAfter(expiresAt);
    }

    public void execute() {
        status = Status.EXECUTED;
        processedAt = Instant.now();
    }

    public void cancel() {
        status = Status.CANCELLED;
        processedAt = Instant.now();
    }

    public void expire() {
        status = Status.EXPIRED;
        processedAt = Instant.now();
    }

    public enum Status {
        PENDING,
        EXECUTED,
        CANCELLED,
        EXPIRED
    }
}
