package tri_lion.health.domain.health;

import jakarta.persistence.*;
import java.time.Instant;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "analyses")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Analysis {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "analysis_id")
    private Long id;

    @Column(name = "user_id")
    private Long userId;

    @Column(name = "analysis_type")
    private String analysisType;

    private String summary;

    @JdbcTypeCode(SqlTypes.JSON)
    private String details;

    @Enumerated(EnumType.STRING)
    private Status status;

    private int progress;

    @Column(name = "failure_reason")
    private String failureReason;

    @Column(name = "model_version")
    private String modelVersion;

    @Column(name = "prompt_version")
    private String promptVersion;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Column(name = "created_at")
    private Instant createdAt;

    public Analysis(Long userId) {
        this.userId = userId;
        analysisType = "HEALTH_ANALYSIS";
        status = Status.PENDING;
        createdAt = Instant.now();
    }

    public void processing() {
        status = Status.PROCESSING;
        progress = 50;
    }

    public void complete(String summary, String details) {
        this.summary = summary;
        this.details = details;
        status = Status.COMPLETED;
        progress = 100;
        modelVersion = "fake-v1";
        promptVersion = "health-v1";
        completedAt = Instant.now();
    }

    public void fail(String reason) {
        status = Status.FAILED;
        failureReason = reason;
    }

    public enum Status {
        PENDING,
        PROCESSING,
        COMPLETED,
        FAILED
    }
}
