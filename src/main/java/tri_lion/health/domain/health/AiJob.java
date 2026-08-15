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
        complete(null, null);
    }

    public void complete(String model, String prompt) {
        status = Status.COMPLETED;
        progress = 100;
        if (model != null) modelVersion = model;
        if (prompt != null) promptVersion = prompt;
        updatedAt = Instant.now();
    }

    public void retry(String reason) {
        retry(reason, 1L << (retryCount + 1));
    }

    public void retry(String reason, long delaySeconds) {
        retryCount++;
        failureReason = reason;
        status = retryCount >= 3 ? Status.FAILED : Status.RETRYING;
        nextAttemptAt = Instant.now().plusSeconds(Math.max(1, delaySeconds));
        updatedAt = Instant.now();
    }

    public void fail(String reason) {
        failureReason = reason;
        status = Status.FAILED;
        nextAttemptAt = null;
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
