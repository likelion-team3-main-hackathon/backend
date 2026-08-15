package tri_lion.health.service.health;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tri_lion.health.domain.health.AiJob;
import tri_lion.health.repository.health.HealthRepositories;

@Service
public class AiJobTransactions {
    private static final long PROCESSING_LEASE_SECONDS = 300;
    private final HealthRepositories.Jobs jobs;

    public AiJobTransactions(HealthRepositories.Jobs jobs) {
        this.jobs = jobs;
    }

    public List<Long> claimableIds() {
        Instant now = Instant.now();
        return jobs
                .claimable(now, now.minusSeconds(PROCESSING_LEASE_SECONDS), PageRequest.of(0, 10))
                .stream()
                .map(AiJob::getId)
                .toList();
    }

    @Transactional
    public Optional<JobSnapshot> claim(Long id) {
        AiJob job = jobs.findForUpdateById(id).orElseThrow();
        boolean ready =
                (job.getStatus() == AiJob.Status.PENDING
                                || job.getStatus() == AiJob.Status.RETRYING)
                        && (job.getNextAttemptAt() == null
                                || !job.getNextAttemptAt().isAfter(Instant.now()));
        ready =
                ready
                        || (job.getStatus() == AiJob.Status.PROCESSING
                                && !job.getUpdatedAt()
                                        .isAfter(
                                                Instant.now()
                                                        .minusSeconds(PROCESSING_LEASE_SECONDS)));
        if (!ready) return Optional.empty();
        job.processing();
        return Optional.of(JobSnapshot.from(job));
    }

    @Transactional
    public AiJob.Status retry(JobSnapshot snapshot, String reason) {
        AiJob job = jobs.findForUpdateById(snapshot.id()).orElseThrow();
        long delaySeconds = reason.contains("HTTP 429") ? 65 : reason.contains("HTTP 503") ? 20 : 2;
        job.retry(reason, delaySeconds);
        return job.getStatus();
    }

    public record JobSnapshot(
            Long id, Long userId, AiJob.Type type, String requestJson, Long resultId) {
        static JobSnapshot from(AiJob job) {
            return new JobSnapshot(
                    job.getId(),
                    job.getUserId(),
                    job.getType(),
                    job.getRequestJson(),
                    job.getResultId());
        }

        public AiJob detachedJob() {
            return new AiJob(userId, type, requestJson, resultId, null);
        }
    }
}
