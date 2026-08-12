package tri_lion.health.domain.record;

import jakarta.persistence.*;
import java.time.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "activity_records")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ActivityRecord {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "activity_record_id")
    private Long id;

    @Column(name = "user_id")
    private Long userId;

    @Column(name = "routine_item_id")
    private Long routineItemId;

    @Enumerated(EnumType.STRING)
    @Column(name = "record_type")
    private ActivityType type;

    private String content;

    @Column(name = "actual_value")
    private java.math.BigDecimal actualValue;

    @Column(name = "image_key")
    private String imageKey;

    private String status;

    @JdbcTypeCode(SqlTypes.JSON)
    private String details;

    @Column(name = "energy_level")
    private Integer energyLevel;

    @Column(name = "pain_level")
    private Integer painLevel;

    @Column(name = "condition_memo")
    private String conditionMemo;

    @Column(name = "performed_at")
    private Instant performedAt;

    @Column(name = "created_at")
    private Instant createdAt;

    public ActivityRecord(
            Long u,
            Long item,
            ActivityType type,
            Instant at,
            String details,
            Integer energy,
            Integer pain,
            String memo) {
        userId = u;
        routineItemId = item;
        this.type = type;
        performedAt = at;
        this.details = details;
        energyLevel = energy;
        painLevel = pain;
        conditionMemo = memo;
        status = "COMPLETED";
        createdAt = Instant.now();
    }
}
