package tri_lion.health.service.analysis;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;
import org.junit.jupiter.api.Test;

class AnalysisLabServiceNutritionTests {
    @Test
    void appliesGoalSpecificProteinMultipliers() {
        assertThat(AnalysisLabService.proteinMultiplier("MUSCLE_GAIN")).isEqualTo(1.6);
        assertThat(AnalysisLabService.proteinMultiplier("WEIGHT_LOSS")).isEqualTo(1.6);
        assertThat(AnalysisLabService.proteinMultiplier("REHABILITATION")).isEqualTo(1.2);
        assertThat(AnalysisLabService.proteinMultiplier("HEALTH_METRIC_MANAGEMENT")).isEqualTo(1.0);
        assertThat(AnalysisLabService.proteinMultiplier("MAINTENANCE")).isEqualTo(1.0);
    }

    @Test
    void choosesTheMostNutritionIntensiveGoal() {
        assertThat(
                        AnalysisLabService.primaryNutritionGoal(
                                Set.of("HEALTH_METRIC_MANAGEMENT", "MUSCLE_GAIN")))
                .isEqualTo("MUSCLE_GAIN");
        assertThat(AnalysisLabService.primaryNutritionGoal(Set.of("GENERAL_WELLNESS")))
                .isEqualTo("MAINTENANCE");
    }
}
