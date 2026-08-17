package tri_lion.health.domain.record;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "pose_analyses")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PoseAnalysis {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "pose_analysis_id") private Long id;
    @Column(name = "user_id", nullable = false) private Long userId;
    @Column(name = "routine_item_id") private Long routineItemId;
    @Column(name = "exercise_name", nullable = false) private String exerciseName;
    @Column(name = "image_key", nullable = false) private String imageKey;
    @Column(name = "pose_score", nullable = false) private Integer poseScore;
    @JdbcTypeCode(SqlTypes.JSON) @Column(name = "detected_issues", nullable = false) private String detectedIssues;
    @JdbcTypeCode(SqlTypes.JSON) @Column(nullable = false) private String feedback;
    @Column(nullable = false, precision = 5, scale = 4) private BigDecimal confidence;
    @Column(name = "safety_warning", length = 1000) private String safetyWarning;
    @Column(name = "model_version", nullable = false) private String modelVersion;
    @Column(name = "prompt_version", nullable = false) private String promptVersion;
    @Column(name = "analyzed_at", nullable = false) private Instant analyzedAt;

    public PoseAnalysis(Long userId, Long routineItemId, String exerciseName, String imageKey,
            int poseScore, String detectedIssues, String feedback, BigDecimal confidence,
            String safetyWarning, String modelVersion, String promptVersion) {
        this.userId = userId;
        this.routineItemId = routineItemId;
        this.exerciseName = exerciseName;
        this.imageKey = imageKey;
        this.poseScore = poseScore;
        this.detectedIssues = detectedIssues;
        this.feedback = feedback;
        this.confidence = confidence;
        this.safetyWarning = safetyWarning;
        this.modelVersion = modelVersion;
        this.promptVersion = promptVersion;
        this.analyzedAt = Instant.now();
    }
}
