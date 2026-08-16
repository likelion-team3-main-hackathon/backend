package tri_lion.health.service.routine;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import org.junit.jupiter.api.Test;

class RoutineServiceDateRangeTests {
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void calculatesInclusiveDateRangeBeforeCallingAi() throws Exception {
        Map<String, Object> range =
                RoutineService.routineDateRange(
                        json.readTree(
                                """
                                {"startDate":"2026-08-16","durationWeeks":4}
                                """));

        assertThat(range)
                .containsEntry("startDate", "2026-08-16")
                .containsEntry("expectedEndDate", "2026-09-12")
                .containsEntry("totalDays", 28);
    }
}
