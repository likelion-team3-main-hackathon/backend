package tri_lion.health.dto.request.record;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.List;

public record MealAnalysisUpdateRequest(@NotEmpty List<@Valid Food> foods) {
    public record Food(
            @NotBlank String name,
            @Positive double servingGrams,
            @PositiveOrZero double calories,
            @PositiveOrZero double carbohydrateGrams,
            @PositiveOrZero double proteinGrams,
            @PositiveOrZero double fatGrams) {}
}
