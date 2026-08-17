package tri_lion.health.repository.record;

import java.time.Instant;
import java.util.*;
import org.springframework.data.jpa.repository.JpaRepository;
import tri_lion.health.domain.record.*;

public final class RecordRepositories {
    private RecordRepositories() {}

    public interface Records extends JpaRepository<ActivityRecord, Long> {
        boolean existsByUserIdAndRoutineItemIdAndStatus(Long u, Long i, String s);

        Optional<ActivityRecord> findFirstByUserIdAndRoutineItemIdAndStatus(
                Long u, Long i, String s);

        List<ActivityRecord> findByUserIdAndPerformedAtBetweenOrderByPerformedAtDesc(
                Long u, Instant a, Instant b);

        List<ActivityRecord> findByUserIdAndTypeAndPerformedAtBetweenOrderByPerformedAtDesc(
                Long u, ActivityType type, Instant a, Instant b);
    }

    public interface Coachings extends JpaRepository<Coaching, Long> {
        Optional<Coaching> findFirstByUserIdOrderByCreatedAtDesc(Long u);
    }
}
