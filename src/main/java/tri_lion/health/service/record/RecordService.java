package tri_lion.health.service.record;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import tri_lion.health.domain.health.*;
import tri_lion.health.domain.record.*;
import tri_lion.health.domain.routine.ExerciseItem;
import tri_lion.health.domain.routine.Routine;
import tri_lion.health.dto.request.record.RecordBatchRequest;
import tri_lion.health.dto.request.record.RecordRequest;
import tri_lion.health.exception.ApiException;
import tri_lion.health.external.ai.AiClients;
import tri_lion.health.external.storage.ObjectStorage;
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
    private final ObjectStorage storage;
    private final JdbcTemplate db;

    public RecordService(
            RecordRepositories.Records r,
            RecordRepositories.Coachings c,
            RoutineRepositories.Items i,
            RoutineRepositories.Routines routines,
            HealthRepositories.Jobs j,
            AuthenticatedUser a,
            ObjectMapper o,
            AiClients.LlmClient l,
            ObjectStorage storage,
            JdbcTemplate db) {
        records = r;
        coachings = c;
        items = i;
        this.routines = routines;
        jobs = j;
        auth = a;
        json = o;
        llm = l;
        this.storage = storage;
        this.db = db;
    }

    @Transactional
    public ActivityRecord create(RecordRequest q) {
        Long uid = auth.active().getId();
        return createOne(uid, q, true);
    }

    @Transactional
    public List<ActivityRecord> createBatch(RecordBatchRequest request) {
        Long uid = auth.active().getId();
        List<ActivityRecord> saved = new ArrayList<>();
        for (RecordRequest record : request.records()) saved.add(createOne(uid, record, false));
        ActivityRecord trigger = saved.getLast();
        if ("COMPLETED".equals(trigger.getStatus()))
            createCoachingJob(
                    uid,
                    trigger,
                    Map.of(
                            "recordIds", saved.stream().map(ActivityRecord::getId).toList(),
                            "activityType", trigger.getType(),
                            "batchSize", saved.size()));
        return saved;
    }

    public String uploadImage(MultipartFile image) {
        Long uid = auth.active().getId();
        if (image.isEmpty() || image.getSize() > 10 * 1024 * 1024)
            throw new ApiException(HttpStatus.PAYLOAD_TOO_LARGE, "사진은 10MB 이하만 업로드할 수 있습니다.");
        String contentType = Optional.ofNullable(image.getContentType()).orElse("").toLowerCase();
        byte[] bytes;
        try {
            bytes = image.getBytes();
        } catch (java.io.IOException exception) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "사진을 읽을 수 없습니다.");
        }
        if (!validImage(contentType, bytes))
            throw new ApiException(HttpStatus.BAD_REQUEST, "JPG 또는 PNG 사진만 업로드할 수 있습니다.");
        String extension = contentType.equals("image/png") ? ".png" : ".jpg";
        String key = "activities/" + uid + "/" + UUID.randomUUID() + extension;
        storage.put(key, bytes, contentType);
        return key;
    }

    private ActivityRecord createOne(Long uid, RecordRequest q, boolean createCoaching) {
        ExerciseItem item = null;
        if (q.routineItemId() != null) {
            item =
                    items.findById(q.routineItemId())
                            .filter(x -> x.getDeletedAt() == null)
                            .orElseThrow(() -> ApiException.notFound("루틴 항목을 찾을 수 없습니다."));
            Routine routine =
                    routines.findByIdAndUserIdAndDeletedAtIsNull(item.getRoutineId(), uid)
                            .orElseThrow(() -> ApiException.notFound("루틴 항목을 찾을 수 없습니다."));
            if (routine.getStatus() != Routine.Status.ACTIVE) {
                throw ApiException.conflict("현재 진행 중인 루틴의 항목만 기록할 수 있습니다.");
            }
            if (item.getStatus() == ExerciseItem.Status.COMPLETED) {
                throw ApiException.conflict("이미 완료한 루틴 항목입니다.");
            }
            if (!q.type().name().equals(item.getItemType()))
                throw new ApiException(HttpStatus.BAD_REQUEST, "루틴 항목과 기록 타입이 일치하지 않습니다.");
        }
        boolean skipped = Boolean.TRUE.equals(q.details().get("skipped"));
        String status = skipped ? "SKIPPED" : "COMPLETED";
        if (q.routineItemId() != null
                && records.existsByUserIdAndRoutineItemIdAndStatus(uid, q.routineItemId(), status))
            throw ApiException.conflict(skipped ? "이미 패스한 루틴 항목입니다." : "이미 완료 기록이 등록된 루틴 항목입니다.");
        if (q.imageKey() != null && !q.imageKey().startsWith("activities/" + uid + "/"))
            throw ApiException.forbidden("다른 사용자의 인증 사진은 사용할 수 없습니다.");
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
                                    q.imageKey(),
                                    status,
                                    c == null ? null : c.energyLevel(),
                                    c == null ? null : c.painLevel(),
                                    c == null ? null : c.memo()));
            if (item != null) {
                if (skipped) item.skip();
                else item.complete();
            }
            if (!skipped && q.type() == ActivityType.WEIGHT) {
                saveWeightMetric(uid, q);
            }
            if (createCoaching && !skipped && q.type() != ActivityType.OTHER)
                createCoachingJob(
                        uid,
                        r,
                        Map.of(
                                "recordId", r.getId(),
                                "activityType", q.type(),
                                "details", q.details()));
            return r;
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException(e);
        }
    }

    private void saveWeightMetric(Long userId, RecordRequest request) {
        Object raw =
                request.details().containsKey("weightKg")
                        ? request.details().get("weightKg")
                        : request.details().get("value");
        BigDecimal weight;
        try {
            weight = new BigDecimal(String.valueOf(raw));
        } catch (Exception exception) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "체중 기록에는 weightKg 값이 필요합니다.");
        }
        if (weight.compareTo(new BigDecimal("20")) < 0
                || weight.compareTo(new BigDecimal("500")) > 0) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "체중은 20~500kg 범위여야 합니다.");
        }
        db.update(
                "insert into health_records(user_id,metric_type,metric_value,unit,input_source,measured_at) values(?,?,?,?,?,?)",
                userId,
                "WEIGHT",
                weight,
                "KG",
                "CHATBOT",
                request.recordedAt().toInstant());
        db.update(
                "update user_health_profiles set weight_kg=?,updated_at=? where user_id=?",
                weight,
                Instant.now(),
                userId);
    }

    private void createCoachingJob(Long uid, ActivityRecord record, Map<String, Object> request) {
        try {
            jobs.save(
                    new AiJob(
                            uid,
                            AiJob.Type.RECORD_COACHING,
                            json.writeValueAsString(request),
                            record.getId(),
                            "record-" + record.getId()));
        } catch (com.fasterxml.jackson.core.JsonProcessingException exception) {
            throw new IllegalArgumentException(exception);
        }
    }

    private boolean validImage(String contentType, byte[] bytes) {
        if (bytes.length < 4) return false;
        boolean jpg = (bytes[0] & 255) == 0xff && (bytes[1] & 255) == 0xd8;
        boolean png =
                (bytes[0] & 255) == 0x89
                        && bytes[1] == 0x50
                        && bytes[2] == 0x4e
                        && bytes[3] == 0x47;
        return (jpg && List.of("image/jpeg", "image/jpg").contains(contentType))
                || (png && contentType.equals("image/png"));
    }

    public List<ActivityRecord> list(LocalDate date, ActivityType type) {
        Long uid = auth.active().getId();
        Instant from = date.atStartOfDay(ZoneId.of("Asia/Seoul")).toInstant();
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
