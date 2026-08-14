package tri_lion.health.dto.request.record;

import jakarta.validation.constraints.*;
import java.time.OffsetDateTime;
import java.util.Map;
import tri_lion.health.domain.record.ActivityType;

public record RecordRequest(
        Long routineItemId,
        @NotNull ActivityType type,
        @NotNull OffsetDateTime recordedAt,
        @NotNull Map<String, Object> details,
        String imageKey,
        Condition condition) {
    public record Condition(
            @Min(1) @Max(5) Integer energyLevel, @Min(0) @Max(5) Integer painLevel, String memo) {}
}
