package tri_lion.health.dto.request.record;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import java.util.List;

public record RecordBatchRequest(@NotEmpty @Size(max = 100) List<@Valid RecordRequest> records) {}
