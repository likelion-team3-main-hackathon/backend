package tri_lion.health.domain.expert;

import jakarta.persistence.*;
import java.time.Instant;
import java.time.LocalTime;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import tri_lion.health.domain.record.ActivityType;

@Entity
@Table(name = "curriculum_items")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class CurriculumItem {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "curriculum_item_id")
    private Long id;

    @Column(name = "curriculum_id")
    private Long curriculumId;

    @Column(name = "week_number")
    private int week;

    @Column(name = "sort_order")
    private int sortOrder;

    @Enumerated(EnumType.STRING)
    @Column(name = "activity_type")
    private ActivityType activityType;

    private String title;
    private String description;

    @Column(name = "scheduled_time")
    private LocalTime scheduledTime;

    @Column(name = "duration_minutes")
    private Integer durationMinutes;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "details_json")
    private String detailsJson;

    @Column(name = "media_url")
    private String mediaUrl;

    @Column(name = "created_at")
    private Instant createdAt;
}
