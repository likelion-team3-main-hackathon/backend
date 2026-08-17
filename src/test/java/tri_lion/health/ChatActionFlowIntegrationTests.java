package tri_lion.health;

import static org.assertj.core.api.Assertions.*;

import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;
import tri_lion.health.dto.chat.ChatDtos.*;
import tri_lion.health.service.chat.*;

@SpringBootTest(
        properties = {"spring.jpa.hibernate.ddl-auto=validate", "spring.flyway.enabled=true"})
@ActiveProfiles("test")
@Transactional
class ChatActionFlowIntegrationTests {
    @Autowired ChatActionService actions;
    @Autowired AiReadToolService readTools;
    @Autowired JdbcTemplate db;
    @Autowired EntityManager entityManager;

    private long userId;
    private long otherUserId;
    private long analysisId;

    @BeforeEach
    void prepareUsers() {
        userId = 9_100_001L;
        otherUserId = 9_100_002L;
        insertUser(userId, "chat-owner");
        insertUser(otherUserId, "chat-other");
        analysisId = 9_200_001L;
        db.update(
                "insert into analyses(analysis_id,user_id,analysis_type,summary,details,status,progress,source_document_ids,completed_at,created_at) values(?,?,?,?,?,'COMPLETED',100,?,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",
                analysisId,
                userId,
                "WELLNESS",
                "무릎 부담을 낮춘 운동과 균형 식단이 필요합니다.",
                "{}",
                "[]");
        authenticate(userId);
    }

    @AfterEach
    void clearAuthentication() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void validatesTwiceAndPersistsEverySupportedCoreFlow() {
        assertThatThrownBy(() -> actions.prepare(decision("unknownService.deleteAll", Map.of())))
                .hasMessageContaining("지원하지 않는 작업");

        assertThatThrownBy(
                        () ->
                                actions.prepare(
                                        decision(
                                                "recordService.create",
                                                Map.of(
                                                        "type",
                                                        "WEIGHT",
                                                        "details",
                                                        Map.of("weightKg", 5)))))
                .hasMessageContaining("허용 범위");

        var weightProposal =
                actions.prepare(
                        decision(
                                "recordService.create",
                                Map.of("type", "WEIGHT", "details", Map.of("weightKg", 70.5))));
        assertThat(weightProposal.getStatus().name()).isEqualTo("PENDING");
        assertThat(
                        db.queryForObject(
                                "select count(*) from health_records where user_id=?",
                                Integer.class,
                                userId))
                .isZero();

        actions.confirm(weightProposal.getId());
        assertThat(
                        db.queryForObject(
                                "select metric_value from health_records where user_id=? and metric_type='WEIGHT'",
                                BigDecimal.class,
                                userId))
                .isEqualByComparingTo("70.50");
        assertThat(
                        db.queryForObject(
                                "select status from pending_ai_actions where pending_ai_action_id=?",
                                String.class,
                                weightProposal.getId()))
                .isEqualTo("EXECUTED");
        assertThatThrownBy(() -> actions.confirm(weightProposal.getId()))
                .hasMessageContaining("이미 처리된 변경안");

        long routineId = createRoutine();
        long itemId =
                db.queryForObject(
                        "select routine_item_id from routine_items where personalized_routine_id=? and item_type='EXERCISE'",
                        Long.class,
                        routineId);

        var patchProposal =
                actions.prepare(
                        decision(
                                "routineService.patchExercise",
                                Map.of(
                                        "routineId",
                                        routineId,
                                        "exerciseId",
                                        itemId,
                                        "targetValue",
                                        15,
                                        "targetUnit",
                                        "MINUTES")));
        actions.confirm(patchProposal.getId());
        assertThat(
                        db.queryForObject(
                                "select target_value from routine_items where routine_item_id=?",
                                BigDecimal.class,
                                itemId))
                .isEqualByComparingTo("15.00");

        var routinePatchProposal =
                actions.prepare(
                        decision(
                                "routineService.patch",
                                Map.of("routineId", routineId, "title", "수정된 저강도 루틴")));
        actions.confirm(routinePatchProposal.getId());
        assertThat(
                        db.queryForObject(
                                "select title from personalized_routines where personalized_routine_id=?",
                                String.class,
                                routineId))
                .isEqualTo("수정된 저강도 루틴");

        var exerciseRecordProposal =
                actions.prepare(
                        decision(
                                "recordService.create",
                                Map.of(
                                        "routineItemId",
                                        itemId,
                                        "type",
                                        "EXERCISE",
                                        "details",
                                        Map.of("minutes", 15))));
        actions.confirm(exerciseRecordProposal.getId());
        assertThat(
                        db.queryForObject(
                                "select count(*) from activity_records where user_id=? and routine_item_id=? and status='COMPLETED'",
                                Integer.class,
                                userId,
                                itemId))
                .isEqualTo(1);

        var adjustmentProposal =
                actions.prepare(
                        decision(
                                "routineService.adjust",
                                Map.of("routineId", routineId, "reason", "최근 수행 결과에 맞춘 강도 재조정")));
        actions.confirm(adjustmentProposal.getId());
        assertThat(
                        db.queryForObject(
                                "select count(*) from ai_jobs where user_id=? and job_type='ROUTINE_ADJUSTMENT'",
                                Integer.class,
                                userId))
                .isEqualTo(1);

        long marketItemId = 9_400_001L;
        db.update(
                "insert into market_items(market_item_id,name,item_type,price,provider_name,status) values(?,?,'MEAL',?,?, 'ACTIVE')",
                marketItemId,
                "닭가슴살",
                5000,
                "TEST_PARTNER");
        var cartProposal =
                actions.prepare(
                        decision(
                                "expansionService.createMealCart",
                                Map.of(
                                        "routineId",
                                        routineId,
                                        "partner",
                                        "TEST_PARTNER",
                                        "items",
                                        List.of(
                                                Map.of(
                                                        "marketItemId",
                                                        marketItemId,
                                                        "quantity",
                                                        2)))));
        actions.confirm(cartProposal.getId());
        assertThat(
                        db.queryForObject(
                                "select count(*) from meal_cart_items item join meal_carts cart on cart.meal_cart_id=item.meal_cart_id where cart.user_id=? and item.market_item_id=?",
                                Integer.class,
                                userId,
                                marketItemId))
                .isEqualTo(1);

        long curriculumId = createCurriculum();
        var personalization =
                actions.prepare(
                        decision(
                                "routineService.personalizeCurriculum",
                                Map.of(
                                        "curriculumId",
                                        curriculumId,
                                        "analysisId",
                                        analysisId,
                                        "startDate",
                                        LocalDate.now().toString(),
                                        "durationWeeks",
                                        1,
                                        "excludedItemIds",
                                        List.of(),
                                        "replacementItems",
                                        List.of())));
        actions.confirm(personalization.getId());
        assertThat(
                        db.queryForObject(
                                "select count(*) from personalized_routines where user_id=? and source_curriculum_id=?",
                                Integer.class,
                                userId,
                                curriculumId))
                .isEqualTo(1);

        db.update(
                "update personalized_routines set status='COMPLETED' where personalized_routine_id=?",
                routineId);
        entityManager.clear();
        assertThatThrownBy(
                        () ->
                                actions.prepare(
                                        decision(
                                                "routineService.patchExercise",
                                                Map.of(
                                                        "routineId",
                                                        routineId,
                                                        "exerciseId",
                                                        itemId,
                                                        "targetValue",
                                                        10))))
                .hasMessageContaining("완료된 루틴");
    }

    @Test
    void readToolsAlwaysRestrictPersonalDataByInjectedUserId() {
        db.update(
                "insert into health_records(user_id,metric_type,metric_value,unit,input_source,measured_at) values(?,'WEIGHT',70,'KG','TEST',CURRENT_TIMESTAMP)",
                userId);
        db.update(
                "insert into health_records(user_id,metric_type,metric_value,unit,input_source,measured_at) values(?,'WEIGHT',99,'KG','TEST',CURRENT_TIMESTAMP)",
                otherUserId);

        List<LookupResult> results =
                readTools.execute(
                        userId,
                        List.of(new LookupRequest("get_recent_records", Map.of("days", 7))));
        Map<?, ?> data = (Map<?, ?>) results.getFirst().data();
        List<?> metrics = (List<?>) data.get("healthMetrics");

        assertThat(metrics).hasSize(1);
        assertThat(String.valueOf(metrics.getFirst())).contains("70").doesNotContain("99");

        assertThatThrownBy(
                        () ->
                                readTools.execute(
                                        userId,
                                        List.of(
                                                new LookupRequest(
                                                        "get_recent_records",
                                                        Map.of("userId", otherUserId)))))
                .hasMessageContaining("허용되지 않은 조건");
    }

    @Test
    void rejectsMissingInputsAndOtherUsersTargets() {
        assertThatThrownBy(
                        () ->
                                actions.prepare(
                                        decision(
                                                "routineService.patchExercise",
                                                Map.of("targetValue", 15))))
                .hasMessageContaining("routineId 값이 필요");

        assertThatThrownBy(
                        () ->
                                actions.prepare(
                                        decision(
                                                "routineService.patch",
                                                Map.of(
                                                        "routineId",
                                                        9_999_999_999L,
                                                        "title",
                                                        "존재하지 않는 루틴"))))
                .hasMessageContaining("루틴을 찾을 수 없습니다");

        long ownerRoutineId = createRoutine();
        authenticate(otherUserId);
        assertThatThrownBy(
                        () ->
                                actions.prepare(
                                        decision(
                                                "routineService.patch",
                                                Map.of(
                                                        "routineId",
                                                        ownerRoutineId,
                                                        "title",
                                                        "남의 루틴 변경"))))
                .hasMessageContaining("루틴을 찾을 수 없습니다");
    }

    @Test
    void revalidatesCurrentStateAtConfirmationAndSupportsCancellation() {
        long routineId = createRoutine();
        long itemId =
                db.queryForObject(
                        "select routine_item_id from routine_items where personalized_routine_id=? and item_type='EXERCISE'",
                        Long.class,
                        routineId);
        var proposal =
                actions.prepare(
                        decision(
                                "routineService.patchExercise",
                                Map.of(
                                        "routineId",
                                        routineId,
                                        "exerciseId",
                                        itemId,
                                        "targetValue",
                                        10)));

        db.update(
                "update personalized_routines set status='COMPLETED' where personalized_routine_id=?",
                routineId);
        entityManager.clear();
        assertThatThrownBy(() -> actions.confirm(proposal.getId())).hasMessageContaining("완료된 루틴");
        assertThat(
                        db.queryForObject(
                                "select status from pending_ai_actions where pending_ai_action_id=?",
                                String.class,
                                proposal.getId()))
                .isEqualTo("PENDING");

        var weightProposal =
                actions.prepare(
                        decision(
                                "recordService.create",
                                Map.of("type", "WEIGHT", "details", Map.of("weightKg", 71.2))));
        actions.cancel(weightProposal.getId());
        assertThatThrownBy(() -> actions.confirm(weightProposal.getId()))
                .hasMessageContaining("이미 처리된 변경안");
    }

    @Test
    void combinesSeveralRoutineItemChangesIntoOneConfirmedActionPlan() {
        long routineId = createRoutine();
        long mealItemId =
                db.queryForObject(
                        "select routine_item_id from routine_items where personalized_routine_id=? and item_type='MEAL'",
                        Long.class,
                        routineId);
        long exerciseItemId =
                db.queryForObject(
                        "select routine_item_id from routine_items where personalized_routine_id=? and item_type='EXERCISE'",
                        Long.class,
                        routineId);

        AiDecision plan =
                new AiDecision(
                        "ACTION_PROPOSAL",
                        "두 항목을 한 번에 바꿉니다.",
                        List.of(
                                new AiOperation(
                                        "routineService.patchRoutineItem",
                                        Map.of(
                                                "routineId",
                                                routineId,
                                                "routineItemId",
                                                mealItemId,
                                                "title",
                                                "그릭 요거트와 초코 토핑",
                                                "content",
                                                "그릭 요거트, 초코 토핑")),
                                new AiOperation(
                                        "routineService.patchRoutineItem",
                                        Map.of(
                                                "routineId",
                                                routineId,
                                                "routineItemId",
                                                exerciseItemId,
                                                "targetValue",
                                                15,
                                                "targetUnit",
                                                "MINUTES"))),
                        "두 항목을 함께 변경할까요?");

        var proposal = actions.prepare(plan);
        assertThat(proposal.getMethodName()).isEqualTo("CHAT_ACTION_PLAN");
        assertThat(proposal.getArgumentsJson()).contains("operations");

        actions.confirm(proposal.getId());

        assertThat(
                        db.queryForObject(
                                "select title from routine_items where routine_item_id=?",
                                String.class,
                                mealItemId))
                .isEqualTo("그릭 요거트와 초코 토핑");
        assertThat(
                        db.queryForObject(
                                "select target_value from routine_items where routine_item_id=?",
                                BigDecimal.class,
                                exerciseItemId))
                .isEqualByComparingTo("15.00");
    }

    private long createRoutine() {
        var proposal =
                actions.prepare(
                        decision(
                                "routineService.createGeneratedRoutine",
                                Map.of(
                                        "analysisId",
                                        analysisId,
                                        "title",
                                        "AI 저강도 루틴",
                                        "goal",
                                        "무릎 부담 감소",
                                        "startDate",
                                        LocalDate.now().toString(),
                                        "durationWeeks",
                                        1,
                                        "items",
                                        List.of(
                                                Map.ofEntries(
                                                        Map.entry("dayOffset", 0),
                                                        Map.entry("sectionType", "MAIN"),
                                                        Map.entry("sectionTitle", "오늘 운동"),
                                                        Map.entry("itemType", "EXERCISE"),
                                                        Map.entry("title", "저강도 걷기"),
                                                        Map.entry("content", "통증이 없을 때만 진행"),
                                                        Map.entry("scheduledTime", "18:00"),
                                                        Map.entry("targetValue", 20),
                                                        Map.entry("targetUnit", "MINUTES"),
                                                        Map.entry("sets", 1),
                                                        Map.entry("restSeconds", 0)),
                                                Map.ofEntries(
                                                        Map.entry("dayOffset", 0),
                                                        Map.entry("sectionType", "MEAL"),
                                                        Map.entry("sectionTitle", "저녁 식단"),
                                                        Map.entry("itemType", "MEAL"),
                                                        Map.entry("title", "닭가슴살 샐러드"),
                                                        Map.entry("content", "고단백 저녁"),
                                                        Map.entry("scheduledTime", "19:00"),
                                                        Map.entry("targetValue", 450),
                                                        Map.entry("targetUnit", "KCAL"))))));
        actions.confirm(proposal.getId());
        return db.queryForObject(
                "select max(personalized_routine_id) from personalized_routines where user_id=?",
                Long.class,
                userId);
    }

    private long createCurriculum() {
        long curriculumId = 9_500_001L;
        db.update(
                "insert into experts(user_id,specialty,qualification_info,verification_status,applied_at) values(?,?,?,'APPROVED',CURRENT_TIMESTAMP)",
                userId,
                "재활",
                "test");
        db.update(
                "insert into market_items(market_item_id,name,item_type,price,provider_name,status) values(?,?,'CURRICULUM',0,'TEST','ACTIVE')",
                curriculumId,
                "허리 재활 프로그램");
        db.update(
                "insert into curricula(market_item_id,expert_id,category,curriculum_type,difficulty,duration_days,description) values(?,?,?,'EXERCISE','BEGINNER',7,?)",
                curriculumId,
                userId,
                "REHABILITATION",
                "저강도 재활");
        db.update(
                "insert into curriculum_items(curriculum_item_id,curriculum_id,week_number,sort_order,activity_type,title,duration_minutes,details_json,created_at) values(?,?,?,?,?,?,?, ?,CURRENT_TIMESTAMP)",
                9_500_002L,
                curriculumId,
                1,
                1,
                "EXERCISE",
                "코어 호흡",
                10,
                "{}");
        db.update(
                "insert into enrollments(user_id,content_id,access_type,personalized,progress_rate,status,started_at) values(?,?,'PURCHASE',true,0,'ACTIVE',CURRENT_DATE)",
                userId,
                curriculumId);
        return curriculumId;
    }

    private AiDecision decision(String methodName, Map<String, Object> arguments) {
        return new AiDecision("ACTION_PROPOSAL", "변경안을 확인해 주세요.", methodName, arguments, "실행할까요?");
    }

    private void insertUser(long id, String suffix) {
        db.update(
                "insert into users(user_id,google_user_id,email,name,nickname,health_goal,credit_balance,role,status,onboarding_completed,created_at,updated_at) values(?,?,?,?,?,?,0,'USER','ACTIVE',true,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",
                id,
                suffix,
                suffix + "@example.com",
                suffix,
                suffix,
                "건강 관리");
        db.update(
                "insert into user_agreements(user_id,type,version,agreed,agreed_at) values(?,'SENSITIVE_HEALTH_DATA','1',true,CURRENT_TIMESTAMP)",
                id);
        db.update(
                "insert into user_health_profiles(user_id,height_cm,weight_kg,goals,injuries,updated_at) values(?,170,72,'[]','[]',CURRENT_TIMESTAMP)",
                id);
    }

    private void authenticate(long id) {
        SecurityContextHolder.getContext()
                .setAuthentication(
                        new UsernamePasswordAuthenticationToken(
                                String.valueOf(id), null, List.of()));
    }
}
