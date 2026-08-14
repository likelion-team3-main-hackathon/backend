package tri_lion.health.domain.routine;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import lombok.*;

@Entity
@Table(name = "routine_items")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ExerciseItem {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "routine_item_id")
    private Long id;

    @Column(name = "personalized_routine_id")
    private Long routineId;

    @Column(name = "section_id")
    private Long sectionId;

    @Column(name = "week_number")
    private int week;

    @Column(name = "day_of_week")
    private String dayOfWeek;

    @Column(name = "scheduled_date")
    private LocalDate scheduledDate;

    @Column(name = "estimated_minutes")
    private Integer estimatedMinutes;

    @Column(name = "section_type")
    private String sectionType;

    @Column(name = "section_title")
    private String sectionTitle;

    @Column(name = "section_order")
    private int sectionOrder;

    @Column(name = "item_type")
    private String itemType;

    @Column(name = "title")
    private String name;

    private String content;

    @Column(name = "scheduled_at")
    private Instant scheduledAt;

    @Column(name = "target_value")
    private BigDecimal targetValue;

    @Enumerated(EnumType.STRING)
    @Column(name = "target_unit")
    private Unit targetUnit;

    @Column(name = "sets_count")
    private Integer sets;

    @Column(name = "rest_seconds")
    private Integer restSeconds;

    @Column(name = "video_url")
    private String videoUrl;

    @Column(name = "thumbnail_url")
    private String thumbnailUrl;

    private String memo;

    @Column(name = "sequence")
    private int sortOrder;

    @Enumerated(EnumType.STRING)
    @Column(name = "edited_by")
    private Routine.Editor editedBy;

    @Column(name = "exclude_from_ai_adjustment")
    private boolean excludeFromAiAdjustment;

    @Enumerated(EnumType.STRING)
    private Status status;

    @Column(name = "deleted_at")
    private Instant deletedAt;

    public ExerciseItem(
            Long r,
            Long s,
            int week,
            LocalDate date,
            Integer estimatedMinutes,
            String sectionType,
            String sectionTitle,
            int sectionOrder,
            String n,
            int o,
            BigDecimal v,
            Unit u,
            int sets,
            int rest,
            String video,
            String thumb,
            String memo,
            boolean exclude,
            Routine.Editor editor) {
        this(
                r,
                s,
                week,
                date,
                estimatedMinutes,
                sectionType,
                sectionTitle,
                sectionOrder,
                "EXERCISE",
                n,
                null,
                null,
                o,
                v,
                u,
                sets,
                rest,
                video,
                thumb,
                memo,
                exclude,
                editor);
    }

    public ExerciseItem(
            Long r,
            Long s,
            int week,
            LocalDate date,
            Integer estimatedMinutes,
            String sectionType,
            String sectionTitle,
            int sectionOrder,
            String itemType,
            String n,
            String content,
            Instant scheduledAt,
            int o,
            BigDecimal v,
            Unit u,
            int sets,
            int rest,
            String video,
            String thumb,
            String memo,
            boolean exclude,
            Routine.Editor editor) {
        routineId = r;
        sectionId = s;
        this.week = week;
        scheduledDate = date;
        dayOfWeek = date.getDayOfWeek().name();
        this.estimatedMinutes = estimatedMinutes;
        this.sectionType = sectionType;
        this.sectionTitle = sectionTitle;
        this.sectionOrder = sectionOrder;
        this.itemType = itemType;
        name = n;
        this.content = content;
        this.scheduledAt = scheduledAt;
        sortOrder = o;
        targetValue = v;
        targetUnit = u;
        this.sets = sets;
        restSeconds = rest;
        videoUrl = video;
        thumbnailUrl = thumb;
        this.memo = memo;
        excludeFromAiAdjustment = exclude;
        editedBy = editor;
        status = Status.PENDING;
    }

    public void patch(
            String name,
            BigDecimal value,
            String unit,
            Integer sets,
            Integer rest,
            String video,
            String thumb,
            String memo,
            Boolean exclude) {
        if (name != null) this.name = name;
        if (value != null) targetValue = value;
        if (unit != null) targetUnit = Unit.valueOf(unit);
        if (sets != null) this.sets = sets;
        if (rest != null) restSeconds = rest;
        if (video != null) videoUrl = video;
        if (thumb != null) thumbnailUrl = thumb;
        if (memo != null) this.memo = memo;
        if (exclude != null) excludeFromAiAdjustment = exclude;
        editedBy = Routine.Editor.USER;
    }

    public void order(int i) {
        sortOrder = i;
    }

    public void delete() {
        deletedAt = Instant.now();
    }

    public void complete() {
        status = Status.COMPLETED;
    }

    public void skip() {
        status = Status.SKIPPED;
    }

    public enum Unit {
        SECONDS,
        MINUTES,
        REPETITIONS,
        METERS,
        KILOMETERS,
        KCAL
    }

    public enum Status {
        PENDING,
        COMPLETED,
        SKIPPED
    }
}
