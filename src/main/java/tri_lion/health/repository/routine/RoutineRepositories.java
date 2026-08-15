package tri_lion.health.repository.routine;

import java.time.LocalDate;
import java.util.*;
import org.springframework.data.domain.*;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
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

        @Query(
                "select i from ExerciseItem i where i.routineId in (select r.id from Routine r where r.userId=:userId and r.deletedAt is null) and i.deletedAt is null and i.scheduledDate between :from and :to order by i.scheduledDate, i.sectionOrder, i.sortOrder")
        List<ExerciseItem> findScheduledForUser(
                @Param("userId") Long userId,
                @Param("from") LocalDate from,
                @Param("to") LocalDate to);
    }
}
