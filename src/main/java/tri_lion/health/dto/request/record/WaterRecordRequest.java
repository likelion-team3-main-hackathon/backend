package tri_lion.health.dto.request.record;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.time.OffsetDateTime;

public record WaterRecordRequest(
        @Min(0) @Max(8) int glasses,
        @NotNull OffsetDateTime recordedAt) {}
