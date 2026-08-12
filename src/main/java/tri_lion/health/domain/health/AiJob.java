package tri_lion.health.domain.health;

import jakarta.persistence.*;
import java.time.Instant;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "ai_jobs")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AiJob {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "ai_job_id")
    private Long id;

    @Column(name = "user_id")
    private Long userId;

    @Enumerated(EnumType.STRING)
    @Column(name = "job_type")
    private Type type;

    @Enumerated(EnumType.STRING)
    private Status status;

    private int progress;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "request_json")
    private String requestJson;

    @Column(name = "result_id")
    private Long resultId;

    @Column(name = "retry_count")
    private int retryCount;

    @Column(name = "next_attempt_at")
    private Instant nextAttemptAt;

    @Column(name = "failure_reason")
    private String failureReason;

    @Column(name = "idempotency_key")
    private String idempotencyKey;

    @Column(name = "model_version")
    private String modelVersion;

    @Column(name = "prompt_version")
    private String promptVersion;

    @Column(name = "created_at")
    private Instant createdAt;

    @Column(name = "updated_at")
    private Instant updatedAt;

    public AiJob(Long u, Type t, String req, Long result, String key) {
        userId = u;
        type = t;
        requestJson = req;
        resultId = result;
        idempotencyKey = key;
        status = Status.PENDING;
        createdAt = Instant.now();
        updatedAt = createdAt;
    }

    public void processing() {
        status = Status.PROCESSING;
        progress = 30;
        updatedAt = Instant.now();
    }

    public void result(Long id) {
        resultId = id;
    }

    public void complete() {
        status = Status.COMPLETED;
        progress = 100;
        updatedAt = Instant.now();
    }

    public void retry(String reason) {
        retryCount++;
        failureReason = reason;
        status = retryCount >= 3 ? Status.FAILED : Status.RETRYING;
        nextAttemptAt = Instant.now().plusSeconds(1L << retryCount);
        updatedAt = Instant.now();
    }

    public enum Type {
        HEALTH_ANALYSIS,
        ROUTINE_GENERATION,
        ROUTINE_ADJUSTMENT,
        RECORD_COACHING,
        CONTENT_PERSONALIZATION
    }

    public enum Status {
        PENDING,
        PROCESSING,
        COMPLETED,
        RETRYING,
        FAILED
    }
}
