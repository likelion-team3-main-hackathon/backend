package tri_lion.health.domain.routine;

import jakarta.persistence.*;
import java.time.*;
import lombok.*;

@Entity
@Table(name = "personalized_routines")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Routine {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "personalized_routine_id")
    private Long id;

    @Column(name = "user_id")
    private Long userId;

    @Column(name = "source_curriculum_id")
    private Long sourceCurriculumId;

    @Column(name = "previous_routine_id")
    private Long previousRoutineId;

    private String title;
    private String description;
    private String goal;

    @Enumerated(EnumType.STRING)
    private Type type;

    @Enumerated(EnumType.STRING)
    private Source source;

    @Column(name = "start_date")
    private LocalDate startDate;

    @Column(name = "end_date")
    private LocalDate endDate;

    @Enumerated(EnumType.STRING)
    private Status status;

    @Version private long version;

    @Column(name = "ai_adjustment_allowed")
    private boolean aiAdjustmentAllowed;

    @Enumerated(EnumType.STRING)
    @Column(name = "last_modified_by")
    private Editor lastModifiedBy;

    @Column(name = "created_at")
    private Instant createdAt;

    @Column(name = "updated_at")
    private Instant updatedAt;

    @Column(name = "deleted_at")
    private Instant deletedAt;

    public Routine(Long u, String title, LocalDate start, int weeks, Long previous) {
        this(u, title, start, weeks, previous, Type.EXERCISE);
    }

    public Routine(
            Long u, String title, LocalDate start, int weeks, Long previous, Type routineType) {
        this(
                u,
                title,
                "개인 제약을 반영한 맞춤 웰니스 루틴입니다.",
                start,
                weeks,
                previous,
                null,
                routineType,
                Source.AI_GENERATED);
    }

    public Routine(
            Long u,
            String title,
            String description,
            LocalDate start,
            int weeks,
            Long previous,
            Type routineType) {
        this(u, title, description, start, weeks, previous, null, routineType, Source.AI_GENERATED);
    }

    public Routine(
            Long u,
            String title,
            LocalDate start,
            int weeks,
            Long previous,
            Long sourceCurriculumId,
            Type routineType,
            Source routineSource) {
        this(
                u,
                title,
                "개인 제약을 반영한 맞춤 웰니스 루틴입니다.",
                start,
                weeks,
                previous,
                sourceCurriculumId,
                routineType,
                routineSource);
    }

    public Routine(
            Long u,
            String title,
            String description,
            LocalDate start,
            int weeks,
            Long previous,
            Long sourceCurriculumId,
            Type routineType,
            Source routineSource) {
        userId = u;
        this.title = title;
        this.description = description;
        type = routineType;
        source = routineSource;
        startDate = start;
        endDate = start.plusWeeks(weeks).minusDays(1);
        status = Status.ACTIVE;
        aiAdjustmentAllowed = true;
        lastModifiedBy = Editor.AI;
        previousRoutineId = previous;
        this.sourceCurriculumId = sourceCurriculumId;
        createdAt = Instant.now();
        updatedAt = createdAt;
    }

    public void patch(
            String title, String description, LocalDate end, Boolean allowed, String status) {
        if (title != null) this.title = title;
        if (description != null) this.description = description;
        if (end != null) {
            if (end.isBefore(startDate))
                throw new IllegalArgumentException("종료일은 시작일보다 빠를 수 없습니다.");
            endDate = end;
        }
        if (allowed != null) aiAdjustmentAllowed = allowed;
        if (status != null) this.status = Status.valueOf(status);
        lastModifiedBy = Editor.USER;
        updatedAt = Instant.now();
    }

    public void applyAiMetadata(String description, String goal) {
        if (description != null && !description.isBlank()) this.description = description;
        if (goal != null && !goal.isBlank()) this.goal = goal;
        lastModifiedBy = Editor.AI;
        updatedAt = Instant.now();
    }

    public enum Type {
        MEAL,
        EXERCISE,
        MIXED
    }

    public enum Source {
        AI_GENERATED,
        EXPERT_CURRICULUM,
        USER_CREATED
    }

    public enum Status {
        DRAFT,
        ACTIVE,
        COMPLETED,
        PAUSED
    }

    public enum Editor {
        AI,
        USER,
        EXPERT
    }
}
