package tri_lion.health.service.record;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import tri_lion.health.domain.health.*;
import tri_lion.health.domain.record.*;
import tri_lion.health.domain.routine.ExerciseItem;
import tri_lion.health.dto.request.record.RecordRequest;
import tri_lion.health.exception.ApiException;
import tri_lion.health.external.ai.AiClients;
import tri_lion.health.repository.health.HealthRepositories;
import tri_lion.health.repository.record.RecordRepositories;
import tri_lion.health.repository.routine.RoutineRepositories;
import tri_lion.health.security.AuthenticatedUser;

@Service
public class RecordService {
    private final RecordRepositories.Records records;
    private final RecordRepositories.Coachings coachings;
    private final RoutineRepositories.Items items;
    private final RoutineRepositories.Routines routines;
    private final HealthRepositories.Jobs jobs;
    private final AuthenticatedUser auth;
    private final ObjectMapper json;
    private final AiClients.LlmClient llm;

    public RecordService(
            RecordRepositories.Records r,
            RecordRepositories.Coachings c,
            RoutineRepositories.Items i,
            RoutineRepositories.Routines routines,
            HealthRepositories.Jobs j,
            AuthenticatedUser a,
            ObjectMapper o,
            AiClients.LlmClient l) {
        records = r;
        coachings = c;
        items = i;
        this.routines = routines;
        jobs = j;
        auth = a;
        json = o;
        llm = l;
    }

    @Transactional
    public ActivityRecord create(RecordRequest q) {
        Long uid = auth.active().getId();
        ExerciseItem item = null;
        if (q.routineItemId() != null) {
            item =
                    items.findById(q.routineItemId())
                            .filter(x -> x.getDeletedAt() == null)
                            .orElseThrow(() -> ApiException.notFound("루틴 항목을 찾을 수 없습니다."));
            routines.findByIdAndUserIdAndDeletedAtIsNull(item.getRoutineId(), uid)
                    .orElseThrow(() -> ApiException.notFound("루틴 항목을 찾을 수 없습니다."));
            if (records.existsByUserIdAndRoutineItemIdAndStatus(
                    uid, q.routineItemId(), "COMPLETED"))
                throw ApiException.conflict("이미 완료 기록이 등록된 루틴 항목입니다.");
        }
        try {
            var c = q.condition();
            ActivityRecord r =
                    records.save(
                            new ActivityRecord(
                                    uid,
                                    q.routineItemId(),
                                    q.type(),
                                    q.recordedAt().toInstant(),
                                    json.writeValueAsString(q.details()),
                                    c == null ? null : c.energyLevel(),
                                    c == null ? null : c.painLevel(),
                                    c == null ? null : c.memo()));
            if (item != null && Boolean.TRUE.equals(q.details().get("completed"))) item.complete();
            jobs.save(
                    new AiJob(
                            uid,
                            AiJob.Type.RECORD_COACHING,
                            json.writeValueAsString(
                                    Map.of(
                                            "recordId",
                                            r.getId(),
                                            "activityType",
                                            q.type(),
                                            "details",
                                            q.details())),
                            r.getId(),
                            "record-" + r.getId()));
            return r;
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException(e);
        }
    }

    public List<ActivityRecord> list(LocalDate date, ActivityType type) {
        Long uid = auth.active().getId();
        Instant from = date.atStartOfDay(ZoneOffset.UTC).toInstant();
        Instant to = from.plus(1, java.time.temporal.ChronoUnit.DAYS);
        return type == null
                ? records.findByUserIdAndPerformedAtBetweenOrderByPerformedAtDesc(uid, from, to)
                : records.findByUserIdAndTypeAndPerformedAtBetweenOrderByPerformedAtDesc(
                        uid, type, from, to);
    }

    public Coaching latest() {
        return coachings
                .findFirstByUserIdOrderByCreatedAtDesc(auth.active().getId())
                .orElseThrow(() -> ApiException.notFound("AI 코칭이 없습니다."));
    }

    @Transactional
    public void coach(AiJob job) {
        coachings.save(
                new Coaching(
                        job.getUserId(), job.getResultId(), llm.coaching(job.getRequestJson())));
    }
}
