package tri_lion.health.repository.health;

import java.time.Instant;
import java.util.*;
import org.springframework.data.domain.*;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import tri_lion.health.domain.health.*;

public final class HealthRepositories {
    private HealthRepositories() {}

    public interface Documents extends JpaRepository<HealthDocument, Long> {
        Page<HealthDocument> findByUserIdAndDeletedAtIsNullOrderByCreatedAtDesc(
                Long id, Pageable p);

        Optional<HealthDocument> findByIdAndUserIdAndDeletedAtIsNull(Long id, Long user);
    }

    public interface Analyses extends JpaRepository<Analysis, Long> {
        Optional<Analysis> findByIdAndUserId(Long id, Long user);

        Page<Analysis> findByUserIdOrderByCreatedAtDesc(Long user, Pageable p);

        Page<Analysis> findByUserIdAndStatusOrderByCreatedAtDesc(
                Long user, Analysis.Status status, Pageable p);

        Optional<Analysis> findFirstByUserIdAndStatusOrderByCompletedAtDesc(
                Long user, Analysis.Status status);
    }

    public interface Jobs extends JpaRepository<AiJob, Long> {
        @Query(
                "select j from AiJob j where j.status in ('PENDING','RETRYING') and (j.nextAttemptAt is null or j.nextAttemptAt<=:now) order by j.createdAt")
        List<AiJob> claimable(@Param("now") Instant now, Pageable p);

        Optional<AiJob> findByUserIdAndTypeAndIdempotencyKey(Long u, AiJob.Type t, String key);

        Optional<AiJob> findByIdAndUserId(Long id, Long user);
    }
}
