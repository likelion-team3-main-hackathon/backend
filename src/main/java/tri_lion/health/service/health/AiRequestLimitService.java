package tri_lion.health.service.health;

import java.time.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tri_lion.health.domain.health.AiJob;
import tri_lion.health.exception.RateLimitExceededException;

@Service
public class AiRequestLimitService {
    private final JdbcTemplate db;
    private final boolean enabled;
    private final ZoneId zone;
    private final int globalPerMinute;
    private final int globalPerDay;
    private final int healthAnalysisPerDay;
    private final int routineGenerationPerDay;
    private final int routineAdjustmentPerDay;
    private final int recordCoachingPerDay;

    public AiRequestLimitService(
            JdbcTemplate db,
            @Value("${app.ai.limits.enabled:true}") boolean enabled,
            @Value("${app.ai.limits.zone:Asia/Seoul}") String zone,
            @Value("${app.ai.limits.global-per-minute:30}") int globalPerMinute,
            @Value("${app.ai.limits.global-per-day:100}") int globalPerDay,
            @Value("${app.ai.limits.health-analysis-per-user-day:3}") int healthAnalysisPerDay,
            @Value("${app.ai.limits.routine-generation-per-user-day:5}")
                    int routineGenerationPerDay,
            @Value("${app.ai.limits.routine-adjustment-per-user-day:5}")
                    int routineAdjustmentPerDay,
            @Value("${app.ai.limits.record-coaching-per-user-day:20}") int recordCoachingPerDay) {
        this.db = db;
        this.enabled = enabled;
        this.zone = ZoneId.of(zone);
        this.globalPerMinute = positive(globalPerMinute, "global-per-minute");
        this.globalPerDay = positive(globalPerDay, "global-per-day");
        this.healthAnalysisPerDay = positive(healthAnalysisPerDay, "health-analysis-per-user-day");
        this.routineGenerationPerDay =
                positive(routineGenerationPerDay, "routine-generation-per-user-day");
        this.routineAdjustmentPerDay =
                positive(routineAdjustmentPerDay, "routine-adjustment-per-user-day");
        this.recordCoachingPerDay = positive(recordCoachingPerDay, "record-coaching-per-user-day");
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void lockJobCreation() {
        if (enabled) lockGuard();
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void authorizeJob(Long userId, AiJob.Type type) {
        if (!enabled) return;
        lockGuard();
        Instant dayStart = dayStart();
        long used =
                count(
                        "select count(*) from ai_jobs where user_id=? and job_type=? and created_at>=?",
                        userId,
                        type.name(),
                        dayStart);
        int limit = perUserDailyLimit(type);
        if (used >= limit)
            throw new RateLimitExceededException(
                    "오늘 사용할 수 있는 " + displayName(type) + " 요청 횟수를 모두 사용했습니다.",
                    secondsUntilNextDay());
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void reserveExternalCall(Long userId, AiJob.Type type) {
        if (!enabled) return;
        lockGuard();
        Instant now = Instant.now();
        long minuteUsed =
                count(
                        "select count(*) from ai_api_request_events where created_at>=?",
                        now.minusSeconds(60));
        if (minuteUsed >= globalPerMinute)
            throw new RateLimitExceededException("AI 요청이 일시적으로 많습니다. 잠시 후 다시 시도해 주세요.", 60);

        long dayUsed =
                count("select count(*) from ai_api_request_events where created_at>=?", dayStart());
        if (dayUsed >= globalPerDay)
            throw new RateLimitExceededException(
                    "오늘 사용할 수 있는 서비스 전체 AI 요청 횟수를 모두 사용했습니다.", secondsUntilNextDay());

        db.update(
                "insert into ai_api_request_events(user_id, job_type, created_at) values (?, ?, ?)",
                userId,
                type.name(),
                now);
    }

    private void lockGuard() {
        db.queryForObject(
                "select guard_id from ai_request_limit_guard where guard_id=1 for update",
                Long.class);
    }

    private long count(String sql, Object... args) {
        Long value = db.queryForObject(sql, Long.class, args);
        return value == null ? 0 : value;
    }

    private Instant dayStart() {
        return LocalDate.now(zone).atStartOfDay(zone).toInstant();
    }

    private long secondsUntilNextDay() {
        ZonedDateTime now = ZonedDateTime.now(zone);
        return Math.max(
                1,
                Duration.between(now, now.toLocalDate().plusDays(1).atStartOfDay(zone))
                        .toSeconds());
    }

    private int perUserDailyLimit(AiJob.Type type) {
        return switch (type) {
            case HEALTH_ANALYSIS -> healthAnalysisPerDay;
            case ROUTINE_GENERATION -> routineGenerationPerDay;
            case ROUTINE_ADJUSTMENT -> routineAdjustmentPerDay;
            case RECORD_COACHING -> recordCoachingPerDay;
            case CONTENT_PERSONALIZATION -> routineGenerationPerDay;
        };
    }

    private String displayName(AiJob.Type type) {
        return switch (type) {
            case HEALTH_ANALYSIS -> "건강 분석";
            case ROUTINE_GENERATION -> "루틴 생성";
            case ROUTINE_ADJUSTMENT -> "루틴 재조정";
            case RECORD_COACHING -> "기록 코칭";
            case CONTENT_PERSONALIZATION -> "콘텐츠 개인화";
        };
    }

    private static int positive(int value, String property) {
        if (value < 1) throw new IllegalArgumentException(property + "는 1 이상이어야 합니다.");
        return value;
    }
}
