package tri_lion.health.service.health;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class HealthAiTaskServiceDateTests {
    @Test
    void parsesDateOnlyAndOcrDateTimeValues() {
        LocalDate expected = LocalDate.of(2026, 5, 5);

        assertThat(HealthAiTaskService.parseMeasuredDate("2026-05-05")).isEqualTo(expected);
        assertThat(HealthAiTaskService.parseMeasuredDate("2026-05-05 19:45")).isEqualTo(expected);
        assertThat(HealthAiTaskService.parseMeasuredDate("2026-05-05T19:45:00"))
                .isEqualTo(expected);
    }
}
