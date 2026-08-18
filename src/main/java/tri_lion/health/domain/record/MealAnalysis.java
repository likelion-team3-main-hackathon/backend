package tri_lion.health.domain.record;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "meal_analyses")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class MealAnalysis {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "meal_analysis_id")
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "routine_item_id")
    private Long routineItemId;

    @Column(name = "image_key", nullable = false)
    private String imageKey;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false)
    private String foods;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false)
    private String totals;

    @Column(nullable = false, precision = 5, scale = 4)
    private BigDecimal confidence;

    @Column(name = "model_version", nullable = false)
    private String modelVersion;

    @Column(name = "confirmed_record_id")
    private Long confirmedRecordId;

    @Column(name = "recorded_at", nullable = false)
    private Instant recordedAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public MealAnalysis(
            Long userId,
            Long routineItemId,
            String imageKey,
            String foods,
            String totals,
            BigDecimal confidence,
            String modelVersion,
            Instant recordedAt) {
        this.userId = userId;
        this.routineItemId = routineItemId;
        this.imageKey = imageKey;
        this.foods = foods;
        this.totals = totals;
        this.confidence = confidence;
        this.modelVersion = modelVersion;
        this.recordedAt = recordedAt;
        status = Status.COMPLETED;
        createdAt = Instant.now();
        updatedAt = createdAt;
    }

    public void revise(String foods, String totals) {
        if (status == Status.CONFIRMED) throw new IllegalStateException("이미 확정된 식단 분석입니다.");
        this.foods = foods;
        this.totals = totals;
        updatedAt = Instant.now();
    }

    public void confirm(Long recordId) {
        if (status == Status.CONFIRMED) throw new IllegalStateException("이미 확정된 식단 분석입니다.");
        confirmedRecordId = recordId;
        status = Status.CONFIRMED;
        updatedAt = Instant.now();
    }

    public enum Status {
        COMPLETED,
        CONFIRMED,
        FAILED
    }
}
