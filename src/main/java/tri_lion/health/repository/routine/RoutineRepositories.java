package tri_lion.health.repository.routine;

import java.time.LocalDate;
import java.util.*;
import org.springframework.data.domain.*;
import org.springframework.data.jpa.repository.JpaRepository;
import tri_lion.health.domain.routine.*;

public final class RoutineRepositories {
    private RoutineRepositories() {}

    public interface Routines extends JpaRepository<Routine, Long> {
        Optional<Routine> findByIdAndUserIdAndDeletedAtIsNull(Long id, Long user);

        Page<Routine> findByUserIdAndDeletedAtIsNullOrderByUpdatedAtDesc(Long user, Pageable p);

        Optional<Routine>
                findFirstByUserIdAndStartDateLessThanEqualAndEndDateGreaterThanEqualAndDeletedAtIsNullOrderByCreatedAtDesc(
                        Long user, LocalDate a, LocalDate b);
    }

    public interface Items extends JpaRepository<ExerciseItem, Long> {
        List<ExerciseItem> findByRoutineIdAndDeletedAtIsNullOrderBySortOrder(Long r);

        List<ExerciseItem>
                findByRoutineIdAndDeletedAtIsNullOrderByScheduledDateAscSectionOrderAscSortOrderAsc(
                        Long r);

        List<ExerciseItem> findByRoutineIdAndSectionIdAndDeletedAtIsNullOrderBySortOrder(
                Long routineId, Long sectionId);

        Optional<ExerciseItem> findFirstByRoutineIdAndSectionIdAndDeletedAtIsNull(
                Long routineId, Long sectionId);

        Optional<ExerciseItem> findByIdAndRoutineIdAndDeletedAtIsNull(Long id, Long routine);
    }
}
