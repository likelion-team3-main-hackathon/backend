package tri_lion.health;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;
import tri_lion.health.domain.health.AiJob;
import tri_lion.health.exception.RateLimitExceededException;
import tri_lion.health.service.health.AiRequestLimitService;

@SpringBootTest(
        properties = {
            "spring.jpa.hibernate.ddl-auto=validate",
            "spring.flyway.enabled=true",
            "app.ai.limits.health-analysis-per-user-day=1",
            "app.ai.limits.global-per-minute=2",
            "app.ai.limits.global-per-day=3"
        })
@ActiveProfiles("test")
class AiRequestLimitIntegrationTests {
    @Autowired JdbcTemplate db;
    @Autowired TransactionTemplate transactions;
    @Autowired AiRequestLimitService limits;

    @Test
    void enforcesPerUserJobAndGlobalExternalRequestLimits() {
        long userId = createUser();

        transactions.executeWithoutResult(
                ignored -> {
                    limits.authorizeJob(userId, AiJob.Type.HEALTH_ANALYSIS);
                    insertJob(userId, AiJob.Type.HEALTH_ANALYSIS);
                });

        assertThatThrownBy(
                        () ->
                                transactions.executeWithoutResult(
                                        ignored ->
                                                limits.authorizeJob(
                                                        userId, AiJob.Type.HEALTH_ANALYSIS)))
                .isInstanceOf(RateLimitExceededException.class)
                .hasMessageContaining("건강 분석");

        limits.reserveExternalCall(userId, AiJob.Type.HEALTH_ANALYSIS);
        limits.reserveExternalCall(userId, AiJob.Type.HEALTH_ANALYSIS);

        assertThatThrownBy(() -> limits.reserveExternalCall(userId, AiJob.Type.HEALTH_ANALYSIS))
                .isInstanceOf(RateLimitExceededException.class)
                .hasMessageContaining("일시적으로 많습니다");
        assertThat(db.queryForObject("select count(*) from ai_api_request_events", Integer.class))
                .isEqualTo(2);
    }

    private long createUser() {
        GeneratedKeyHolder keys = new GeneratedKeyHolder();
        Instant now = Instant.now();
        db.update(
                connection -> {
                    PreparedStatement statement =
                            connection.prepareStatement(
                                    "insert into users(google_user_id,email,name,nickname,credit_balance,role,status,onboarding_completed,created_at,updated_at) values (?,?,?,?,0,'USER','ACTIVE',true,?,?)",
                                    Statement.RETURN_GENERATED_KEYS);
                    statement.setString(1, "limit-test-user");
                    statement.setString(2, "limit@example.com");
                    statement.setString(3, "Limit Test");
                    statement.setString(4, "limit-test-user");
                    statement.setTimestamp(5, Timestamp.from(now));
                    statement.setTimestamp(6, Timestamp.from(now));
                    return statement;
                },
                keys);
        return keys.getKey().longValue();
    }

    private void insertJob(long userId, AiJob.Type type) {
        Instant now = Instant.now();
        db.update(
                "insert into ai_jobs(user_id,job_type,status,progress,request_json,retry_count,created_at,updated_at) values (?,?,'COMPLETED',100,'{}',0,?,?)",
                userId,
                type.name(),
                now,
                now);
    }
}
