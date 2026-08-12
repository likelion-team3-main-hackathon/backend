package tri_lion.health.dto.request.expert;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import tri_lion.health.domain.expert.CurriculumType;
import tri_lion.health.domain.record.ActivityType;

public record ExpertContentRequest(
        @NotBlank String title,
        @NotBlank String description,
        @NotBlank String category,
        @NotBlank String difficulty,
        @NotNull CurriculumType contentType,
        @Min(1) @Max(52) int durationWeeks,
        @Min(1) @Max(21) int sessionsPerWeek,
        @PositiveOrZero long price,
        List<String> targetGoals,
        List<String> contraindications,
        @NotEmpty List<@Valid Item> items) {

    public record Item(
            @Min(1) int week,
            @Min(1) int order,
            @NotNull ActivityType activityType,
            @NotBlank String title,
            String description,
            LocalTime scheduledTime,
            @Positive Integer durationMinutes,
            Map<String, Object> details,
            String mediaUrl) {}
}
