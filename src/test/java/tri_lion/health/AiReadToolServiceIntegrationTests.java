package tri_lion.health;

import static org.assertj.core.api.Assertions.*;
import static tri_lion.health.dto.chat.ChatDtos.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;
import tri_lion.health.exception.ApiException;
import tri_lion.health.service.chat.AiReadToolService;

@SpringBootTest(
        properties = {"spring.jpa.hibernate.ddl-auto=validate", "spring.flyway.enabled=true"})
@ActiveProfiles("test")
@Transactional
class AiReadToolServiceIntegrationTests {
    private static final long OWNER_ID = 9_700_001L;
    private static final long OTHER_USER_ID = 9_700_002L;
    private static final long OWNER_ANALYSIS_ID = 9_701_001L;
    private static final long OWNER_ROUTINE_ID = 9_702_001L;
    private static final long OWNER_EXERCISE_ITEM_ID = 9_703_001L;
    private static final long OWNER_MEAL_ITEM_ID = 9_703_002L;
    private static final long CURRICULUM_ID = 9_704_001L;
    private static final long CURRICULUM_ITEM_ID = 9_704_002L;
    private static final long MEAL_PRODUCT_ID = 9_705_001L;

    @Autowired AiReadToolService readTools;
    @Autowired JdbcTemplate db;

    @BeforeEach
    void setUp() {
        insertUser(OWNER_ID, "read-owner", "ACTIVE");
        insertUser(OTHER_USER_ID, "read-other", "ACTIVE");
        insertProfile(OWNER_ID, 70, "[\"WEIGHT_LOSS\"]");
        insertProfile(OTHER_USER_ID, 99, "[\"MUSCLE_GAIN\"]");
        insertAnalyses();
        insertRoutineData();
        insertCurriculumData();
        insertMarketData();
        insertRecords();
    }

    @Test
    void getHealthSummaryReturnsOnlyTheCurrentUsersProfile() {
        List<Map<String, Object>> health = data("get_health_summary", Map.of());

        assertThat(health)
                .singleElement()
                .satisfies(
                        row -> {
                            assertThat(field(row, "healthGoal")).isEqualTo("체중 감량");
                            assertThat(decimal(field(row, "weightKg"))).isEqualByComparingTo("70");
                            assertThat(decimal(field(row, "targetWeightKg")))
                                    .isEqualByComparingTo("65");
                        });
    }

    @Test
    void getLatestAnalysisReturnsTheCurrentUsersLatestCompletedAnalysis() {
        List<Map<String, Object>> analyses = data("get_latest_analysis", Map.of());

        assertThat(analyses)
                .singleElement()
                .satisfies(
                        row -> {
                            assertThat(number(field(row, "analysisId")))
                                    .isEqualTo(OWNER_ANALYSIS_ID);
                            assertThat(field(row, "summary")).isEqualTo("저강도 운동을 권장합니다.");
                        });
    }

    @Test
    void getRoutineItemsSupportsEveryRoutineFilterAndDoesNotLeakOtherUsersItems() {
        List<Map<String, Object>> routineItems =
                data(
                        "get_routine_items",
                        Map.of(
                                "dateFrom", LocalDate.now().toString(),
                                "dateTo", LocalDate.now().plusDays(6).toString(),
                                "itemType", "EXERCISE",
                                "sectionType", "MAIN",
                                "sectionKeyword", "운동",
                                "status", "PENDING",
                                "keyword", "걷기"));

        assertThat(routineItems)
                .singleElement()
                .satisfies(
                        row -> {
                            assertThat(number(field(row, "routineId"))).isEqualTo(OWNER_ROUTINE_ID);
                            assertThat(number(field(row, "routineItemId")))
                                    .isEqualTo(OWNER_EXERCISE_ITEM_ID);
                            assertThat(field(row, "sectionType")).isEqualTo("MAIN");
                            assertThat(field(row, "sectionTitle")).isEqualTo("오늘 운동");
                        });
    }

    @Test
    void getRecentRecordsReturnsOnlyTheCurrentUsersActivityAndHealthRecords() {
        Map<String, Object> records = dataObject("get_recent_records", Map.of("days", 7));

        assertThat(list(records, "activities"))
                .extracting(row -> number(field(row, "routineItemId")))
                .containsExactly(OWNER_EXERCISE_ITEM_ID);
        assertThat(list(records, "healthMetrics"))
                .singleElement()
                .satisfies(
                        row ->
                                assertThat(decimal(field(row, "metricValue")))
                                        .isEqualByComparingTo("70"));
    }

    @Test
    void getActiveCurriculaReturnsOnlyTheCurrentUsersActiveEnrollment() {
        List<Map<String, Object>> activeCurricula = data("get_active_curricula", Map.of());

        assertThat(activeCurricula)
                .extracting(row -> number(field(row, "curriculumId")))
                .containsExactly(CURRICULUM_ID);
    }

    @Test
    void getCurriculumDetailRequiresTheCurrentUsersActiveEnrollment() {
        Map<String, Object> curriculum =
                dataObject("get_curriculum_detail", Map.of("curriculumId", CURRICULUM_ID));

        assertThat(number(field(curriculum, "curriculumId"))).isEqualTo(CURRICULUM_ID);
        assertThat(list(curriculum, "items"))
                .extracting(row -> number(field(row, "itemId")))
                .containsExactly(CURRICULUM_ITEM_ID);

        assertThatThrownBy(
                        () ->
                                readTools.execute(
                                        OTHER_USER_ID,
                                        List.of(
                                                new LookupRequest(
                                                        "get_curriculum_detail",
                                                        Map.of("curriculumId", CURRICULUM_ID)))))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("이용 중인 커리큘럼을 찾을 수 없습니다");
    }

    @Test
    void searchMarketProductsReturnsOnlyAvailableMealProducts() {
        List<Map<String, Object>> products =
                data("search_market_products", Map.of("keywords", List.of("닭가슴살")));

        assertThat(products)
                .extracting(row -> number(field(row, "marketItemId")))
                .containsExactly(MEAL_PRODUCT_ID);
    }

    @Test
    void rejectsBlankRoutineFiltersWithAControlledClientErrorInsteadOfA500() {
        assertThatThrownBy(
                        () ->
                                readTools.execute(
                                        OWNER_ID,
                                        List.of(
                                                new LookupRequest(
                                                        "get_routine_items",
                                                        Map.of("itemType", " ")))))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("itemType 값이 비어 있습니다.");
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> data(String toolName, Map<String, Object> arguments) {
        return (List<Map<String, Object>>)
                readTools
                        .execute(OWNER_ID, List.of(new LookupRequest(toolName, arguments)))
                        .getFirst()
                        .data();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> dataObject(String toolName, Map<String, Object> arguments) {
        return (Map<String, Object>)
                readTools
                        .execute(OWNER_ID, List.of(new LookupRequest(toolName, arguments)))
                        .getFirst()
                        .data();
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> list(Map<String, Object> data, String key) {
        return (List<Map<String, Object>>) data.get(key);
    }

    private Object field(Map<String, Object> row, String key) {
        return row.entrySet().stream()
                .filter(entry -> entry.getKey().equalsIgnoreCase(key))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Missing field: " + key + " in " + row))
                .getValue();
    }

    private long number(Object value) {
        return ((Number) value).longValue();
    }

    private BigDecimal decimal(Object value) {
        return new BigDecimal(String.valueOf(value));
    }

    private void insertUser(long userId, String suffix, String status) {
        db.update(
                """
                insert into users(user_id,google_user_id,email,name,nickname,health_goal,credit_balance,role,status,onboarding_completed,created_at,updated_at)
                values(?,?,?,?,?,'체중 감량',0,'USER',?,true,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)
                """,
                userId,
                suffix,
                suffix + "@example.com",
                suffix,
                suffix,
                status);
    }

    private void insertProfile(long userId, int weight, String goals) {
        db.update(
                """
                insert into user_health_profiles(user_id,height_cm,weight_kg,target_weight_kg,activity_level,available_exercise_minutes,exercise_days,dietary_preferences,allergies,disliked_foods,goals,injuries,updated_at)
                values(?,170,?,65,'MODERATE',30,'[]','[]','[]','[]',?, '[]',CURRENT_TIMESTAMP)
                """,
                userId,
                weight,
                goals);
    }

    private void insertAnalyses() {
        db.update(
                """
                insert into analyses(analysis_id,user_id,analysis_type,summary,details,status,progress,source_document_ids,completed_at,created_at)
                values(?,?, 'WELLNESS', ?, '{}', 'COMPLETED',100,'[]',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)
                """,
                OWNER_ANALYSIS_ID,
                OWNER_ID,
                "저강도 운동을 권장합니다.");
        db.update(
                """
                insert into analyses(analysis_id,user_id,analysis_type,summary,details,status,progress,source_document_ids,completed_at,created_at)
                values(?,?, 'WELLNESS', ?, '{}', 'PENDING',0,'[]',NULL,CURRENT_TIMESTAMP)
                """,
                OWNER_ANALYSIS_ID + 1,
                OWNER_ID,
                "완료 전 분석");
        db.update(
                """
                insert into analyses(analysis_id,user_id,analysis_type,summary,details,status,progress,source_document_ids,completed_at,created_at)
                values(?,?,?,?, '{}', 'COMPLETED',100,'[]',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)
                """,
                OWNER_ANALYSIS_ID + 2,
                OTHER_USER_ID,
                "WELLNESS",
                "다른 사람 분석");
    }

    private void insertRoutineData() {
        insertRoutine(OWNER_ROUTINE_ID, OWNER_ID, "이번 주 루틴", "무릎 부담 감소");
        insertRoutine(OWNER_ROUTINE_ID + 1, OTHER_USER_ID, "다른 사람 루틴", "다른 목표");
        insertRoutineItem(
                OWNER_EXERCISE_ITEM_ID,
                OWNER_ROUTINE_ID,
                "EXERCISE",
                "가벼운 걷기",
                "MAIN",
                "오늘 운동",
                20,
                "MINUTES");
        insertRoutineItem(
                OWNER_MEAL_ITEM_ID,
                OWNER_ROUTINE_ID,
                "MEAL",
                "닭가슴살 샐러드",
                "MEAL",
                "오늘 식단",
                450,
                "KCAL");
        insertRoutineItem(
                OWNER_EXERCISE_ITEM_ID + 10,
                OWNER_ROUTINE_ID + 1,
                "EXERCISE",
                "다른 사람 걷기",
                "MAIN",
                "오늘 운동",
                99,
                "MINUTES");
    }

    private void insertRoutine(long routineId, long userId, String title, String goal) {
        db.update(
                """
                insert into personalized_routines(personalized_routine_id,user_id,title,description,goal,type,source,start_date,end_date,status,version,ai_adjustment_allowed,last_modified_by,created_at,updated_at)
                values(?,?,?,'조회 테스트',?,'MIXED','AI_GENERATED',CURRENT_DATE,DATEADD('DAY',6,CURRENT_DATE),'ACTIVE',0,true,'AI',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)
                """,
                routineId,
                userId,
                title,
                goal);
    }

    private void insertRoutineItem(
            long itemId,
            long routineId,
            String itemType,
            String title,
            String sectionType,
            String sectionTitle,
            int targetValue,
            String targetUnit) {
        db.update(
                """
                insert into routine_items(routine_item_id,personalized_routine_id,item_type,title,content,scheduled_at,target_value,target_unit,sets_count,rest_seconds,sequence,edited_by,exclude_from_ai_adjustment,status,week_number,day_of_week,scheduled_date,estimated_minutes,section_type,section_title,section_order)
                values(?,?,?,?,'테스트 내용',CURRENT_TIMESTAMP,?,?,1,0,1,'AI',false,'PENDING',1,'MONDAY',CURRENT_DATE,20,?,?,1)
                """,
                itemId,
                routineId,
                itemType,
                title,
                targetValue,
                targetUnit,
                sectionType,
                sectionTitle);
    }

    private void insertCurriculumData() {
        db.update(
                """
                insert into experts(user_id,specialty,qualification_info,verification_status,applied_at)
                values(?, '운동','테스트 자격','APPROVED',CURRENT_TIMESTAMP)
                """,
                OWNER_ID);
        db.update(
                """
                insert into market_items(market_item_id,name,item_type,price,provider_name,status)
                values(?, '저강도 필라테스','CURRICULUM',0,'TEST','ACTIVE')
                """,
                CURRICULUM_ID);
        db.update(
                """
                insert into curricula(market_item_id,expert_id,category,curriculum_type,difficulty,duration_days,description)
                values(?,?,'REHABILITATION','EXERCISE','BEGINNER',7,'저강도 수업')
                """,
                CURRICULUM_ID,
                OWNER_ID);
        db.update(
                """
                insert into curriculum_items(curriculum_item_id,curriculum_id,week_number,sort_order,activity_type,title,duration_minutes,details_json,created_at)
                values(?,?,1,1,'EXERCISE','코어 호흡',10,'{}',CURRENT_TIMESTAMP)
                """,
                CURRICULUM_ITEM_ID,
                CURRICULUM_ID);
        db.update(
                """
                insert into enrollments(user_id,content_id,access_type,personalized,progress_rate,status,started_at)
                values(?,?,'PURCHASE',false,0,'ACTIVE',CURRENT_DATE)
                """,
                OWNER_ID,
                CURRICULUM_ID);
    }

    private void insertMarketData() {
        insertMarketItem(MEAL_PRODUCT_ID, "닭가슴살 도시락", "MEAL", "ACTIVE");
        insertMarketItem(MEAL_PRODUCT_ID + 1, "닭가슴살 품절 상품", "MEAL", "INACTIVE");
        insertMarketItem(MEAL_PRODUCT_ID + 2, "닭가슴살 운동기구", "EQUIPMENT", "ACTIVE");
    }

    private void insertMarketItem(long itemId, String name, String itemType, String status) {
        db.update(
                """
                insert into market_items(market_item_id,name,item_type,price,provider_name,status)
                values(?,?,?,5000,'TEST',?)
                """,
                itemId,
                name,
                itemType,
                status);
    }

    private void insertRecords() {
        db.update(
                """
                insert into health_records(user_id,metric_type,metric_value,unit,input_source,measured_at)
                values(?,'WEIGHT',70,'KG','TEST',CURRENT_TIMESTAMP)
                """,
                OWNER_ID);
        db.update(
                """
                insert into health_records(user_id,metric_type,metric_value,unit,input_source,measured_at)
                values(?,'WEIGHT',99,'KG','TEST',CURRENT_TIMESTAMP)
                """,
                OTHER_USER_ID);
        insertActivityRecord(OWNER_ID, OWNER_EXERCISE_ITEM_ID, 20);
        insertActivityRecord(OTHER_USER_ID, OWNER_EXERCISE_ITEM_ID + 10, 99);
    }

    private void insertActivityRecord(long userId, long routineItemId, int actualValue) {
        db.update(
                """
                insert into activity_records(user_id,routine_item_id,record_type,actual_value,status,details,performed_at,created_at)
                values(?,?, 'EXERCISE',?,'COMPLETED','{}',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)
                """,
                userId,
                routineItemId,
                actualValue);
    }
}
