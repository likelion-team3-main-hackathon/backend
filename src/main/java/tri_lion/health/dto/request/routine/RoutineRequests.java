package tri_lion.health.dto.request.routine;

import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

public final class RoutineRequests {
    private RoutineRequests() {}

    public record GenerationRequest(
            @NotNull Long analysisId,
            @NotNull LocalDate startDate,
            @Min(1) @Max(12) int durationWeeks,
            @Min(0) @Max(6) int mealCountPerDay,
            @Min(0) @Max(7) int exerciseDaysPerWeek,
            List<String> preferredExerciseTypes,
            boolean includeExpertContents,
            @Size(max = 2) List<String> selectedRecommendationIds) {}

    public record PatchRoutine(
            String title,
            String description,
            LocalDate endDate,
            Boolean aiAdjustmentAllowed,
            String status) {}

    public record ExerciseRequest(
            @NotBlank String name,
            @Min(1) Integer order,
            @DecimalMin("0.1") BigDecimal targetValue,
            @NotBlank String targetUnit,
            @Min(1) Integer sets,
            @Min(0) Integer restSeconds,
            String videoUrl,
            String thumbnailUrl,
            String memo,
            boolean excludeFromAiAdjustment) {}

    public record PatchExercise(
            String name,
            BigDecimal targetValue,
            String targetUnit,
            Integer sets,
            Integer restSeconds,
            String videoUrl,
            String thumbnailUrl,
            String memo,
            Boolean excludeFromAiAdjustment) {}

    /** 챗봇이 운동과 식단을 같은 방식으로 안전하게 수정할 때 사용하는 공통 요청입니다. */
    public record PatchRoutineItem(
            String title,
            String content,
            BigDecimal targetValue,
            String targetUnit,
            Integer sets,
            Integer restSeconds,
            String memo,
            Boolean excludeFromAiAdjustment) {}

    public record OrderRequest(@NotEmpty List<Long> exerciseIds) {}

    public record AdjustmentRequest(@NotBlank String reason, String userMessage) {}

    public record CurriculumPersonalizationRequest(
            @NotNull Long curriculumId,
            @NotNull Long analysisId,
            @NotNull LocalDate startDate,
            @Min(1) @Max(12) int durationWeeks,
            List<Long> excludedItemIds,
            List<PersonalizedItem> replacementItems) {}

    public record ChatGenerationRequest(
            @NotNull Long analysisId,
            @NotBlank String title,
            String goal,
            @NotNull LocalDate startDate,
            @Min(1) @Max(12) int durationWeeks,
            @NotEmpty @Size(max = 200) List<GeneratedRoutineItem> items) {}

    public record GeneratedRoutineItem(
            @Min(0) int dayOffset,
            @NotBlank String sectionType,
            @NotBlank String sectionTitle,
            @NotBlank String itemType,
            @NotBlank String title,
            String content,
            String scheduledTime,
            @DecimalMin("0.1") BigDecimal targetValue,
            @NotBlank String targetUnit,
            @Min(1) Integer sets,
            @Min(0) Integer restSeconds,
            String memo) {}

    public record PersonalizedItem(
            @NotNull Long sourceItemId,
            @NotBlank String activityType,
            @NotBlank String title,
            String description,
            @Min(1) @Max(300) Integer durationMinutes,
            Map<String, Object> details) {}
}
