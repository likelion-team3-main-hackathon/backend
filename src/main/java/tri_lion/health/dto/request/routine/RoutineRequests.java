package tri_lion.health.dto.request.routine;

import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

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

    public record OrderRequest(@NotEmpty List<Long> exerciseIds) {}

    public record AdjustmentRequest(@NotBlank String reason, String userMessage) {}
}
