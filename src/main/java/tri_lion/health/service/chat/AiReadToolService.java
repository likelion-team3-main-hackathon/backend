package tri_lion.health.service.chat;

import static tri_lion.health.dto.chat.ChatDtos.*;

import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tri_lion.health.exception.ApiException;

@Service
public class AiReadToolService {
    private static final Logger log = LoggerFactory.getLogger(AiReadToolService.class);
    private static final Set<String> ALLOWED_TOOLS =
            Set.of(
                    "get_health_summary",
                    "get_latest_analysis",
                    "get_routine_items",
                    "get_recent_records",
                    "get_active_curricula",
                    "get_curriculum_detail",
                    "search_market_products",
                    "get_health_measurements",
                    "get_health_documents",
                    "get_analysis_history",
                    "get_routine_progress",
                    "get_activity_records",
                    "get_nutrition_summary",
                    "get_exercise_summary",
                    "get_hydration_summary",
                    "get_chat_history",
                    "get_credit_history",
                    "get_notification_settings");
    private static final Map<String, Set<String>> ALLOWED_ARGUMENTS =
            Map.ofEntries(
                    Map.entry("get_health_summary", Set.of()),
                    Map.entry("get_latest_analysis", Set.of()),
                    Map.entry(
                            "get_routine_items",
                            Set.of(
                                    "dateFrom",
                                    "dateTo",
                                    "routineId",
                                    "itemType",
                                    "sectionType",
                                    "sectionKeyword",
                                    "status",
                                    "keyword",
                                    "intensity")),
                    Map.entry("get_recent_records", Set.of("days")),
                    Map.entry("get_active_curricula", Set.of()),
                    Map.entry("get_curriculum_detail", Set.of("curriculumId")),
                    Map.entry("search_market_products", Set.of("keyword", "keywords")),
                    Map.entry(
                            "get_health_measurements",
                            Set.of("metricCode", "category", "dateFrom", "dateTo")),
                    Map.entry("get_health_documents", Set.of("documentType", "status")),
                    Map.entry("get_analysis_history", Set.of("status")),
                    Map.entry("get_routine_progress", Set.of("routineId", "dateFrom", "dateTo")),
                    Map.entry(
                            "get_activity_records",
                            Set.of("dateFrom", "dateTo", "recordType", "minPain")),
                    Map.entry("get_nutrition_summary", Set.of("days")),
                    Map.entry("get_exercise_summary", Set.of("days")),
                    Map.entry("get_hydration_summary", Set.of("days")),
                    Map.entry("get_chat_history", Set.of("days", "keyword")),
                    Map.entry("get_credit_history", Set.of("days")),
                    Map.entry("get_notification_settings", Set.of()));

    private final JdbcTemplate db;

    public AiReadToolService(JdbcTemplate db) {
        this.db = db;
    }

    @Transactional(readOnly = true)
    public List<LookupResult> execute(Long userId, List<LookupRequest> requests) {
        if (requests == null || requests.isEmpty()) return List.of();
        if (requests.size() > 5) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "한 질문에서 조회는 최대 5개까지 가능합니다.");
        }

        List<LookupResult> results = new ArrayList<>();
        for (LookupRequest request : requests) {
            if (request == null || !ALLOWED_TOOLS.contains(request.toolName())) {
                throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "허용되지 않은 AI 조회입니다.");
            }
            Map<String, Object> arguments =
                    request.arguments() == null ? Map.of() : request.arguments();
            if (!ALLOWED_ARGUMENTS.get(request.toolName()).containsAll(arguments.keySet())) {
                throw new ApiException(
                        HttpStatus.UNPROCESSABLE_ENTITY, "AI 조회에 허용되지 않은 조건이 포함되어 있습니다.");
            }
            try {
                results.add(
                        new LookupResult(
                                request.toolName(),
                                arguments,
                                run(userId, request.toolName(), arguments)));
            } catch (DataAccessException exception) {
                log.error(
                        "AI read tool failed: tool={}, userId={}",
                        request.toolName(),
                        userId,
                        exception);
                throw new ApiException(
                        HttpStatus.SERVICE_UNAVAILABLE, "개인화 데이터를 조회하지 못했습니다. 잠시 후 다시 시도해 주세요.");
            }
        }
        return results;
    }

    private Object run(Long userId, String toolName, Map<String, Object> arguments) {
        return switch (toolName) {
            case "get_health_summary" ->
                    db.queryForList(
                            """
                            select health_goal healthGoal,
                                   height_cm heightCm,
                                   weight_kg weightKg,
                                   target_weight_kg targetWeightKg,
                                   activity_level activityLevel,
                                   available_exercise_minutes availableExerciseMinutes,
                                   exercise_days exerciseDays,
                                   dietary_preferences dietaryPreferences,
                                   allergies,
                                   disliked_foods dislikedFoods,
                                   goals,
                                   injuries,
                                   updated_at updatedAt
                            from ai_health_summary_view
                            where user_id=?
                            """,
                            userId);
            case "get_latest_analysis" ->
                    db.queryForList(
                            """
                            select analysis_id analysisId,
                                   analysis_type analysisType,
                                   summary,
                                   details,
                                   completed_at completedAt
                            from ai_analysis_summary_view
                            where user_id=?
                            order by completed_at desc
                            limit 1
                            """,
                            userId);
            case "get_routine_items" -> routineItems(userId, arguments);
            case "get_recent_records" -> recentRecords(userId, arguments);
            case "get_active_curricula" ->
                    db.queryForList(
                            """
                            select enrollment_id enrollmentId,
                                   curriculum_id curriculumId,
                                   curriculum_title curriculumTitle,
                                   progress_rate progressRate,
                                   category,
                                   curriculum_type curriculumType,
                                   difficulty,
                                   duration_days durationDays,
                                   description,
                                   started_at startedAt
                            from ai_active_curriculum_view
                            where user_id=?
                            order by started_at desc
                            limit 30
                            """,
                            userId);
            case "get_curriculum_detail" -> curriculumDetail(userId, arguments);
            case "search_market_products" -> marketProducts(arguments);
            case "get_health_measurements" -> healthMeasurements(userId, arguments);
            case "get_health_documents" -> healthDocuments(userId, arguments);
            case "get_analysis_history" -> analysisHistory(userId, arguments);
            case "get_routine_progress" -> routineProgress(userId, arguments);
            case "get_activity_records" -> activityRecords(userId, arguments);
            case "get_nutrition_summary" -> typedRecords(userId, arguments, "MEAL");
            case "get_exercise_summary" -> typedRecords(userId, arguments, "EXERCISE");
            case "get_hydration_summary" -> hydrationRecords(userId, arguments);
            case "get_chat_history" -> chatHistory(userId, arguments);
            case "get_credit_history" -> creditHistory(userId, arguments);
            case "get_notification_settings" -> notificationSettings(userId);
            default -> throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "지원하지 않는 조회입니다.");
        };
    }

    private Map<String, Object> curriculumDetail(Long userId, Map<String, Object> arguments) {
        long curriculumId = positiveLong(arguments, "curriculumId");
        List<Map<String, Object>> headers =
                db.queryForList(
                        """
                        select e.enrollment_id enrollmentId,
                               e.content_id curriculumId,
                               m.name title,
                               c.category,
                               c.curriculum_type curriculumType,
                               c.difficulty,
                               c.duration_days durationDays,
                               c.description
                        from enrollments e
                        join curricula c on c.market_item_id=e.content_id
                        join market_items m on m.market_item_id=e.content_id
                        where e.user_id=? and e.content_id=? and e.status='ACTIVE'
                        """,
                        userId,
                        curriculumId);
        if (headers.isEmpty()) {
            throw ApiException.notFound("이용 중인 커리큘럼을 찾을 수 없습니다.");
        }
        Map<String, Object> result = new LinkedHashMap<>(headers.getFirst());
        result.put(
                "items",
                db.queryForList(
                        """
                        select curriculum_item_id itemId,
                               week_number weekNumber,
                               sort_order sortOrder,
                               activity_type activityType,
                               title,
                               description,
                               scheduled_time scheduledTime,
                               duration_minutes durationMinutes,
                               details_json details,
                               media_url mediaUrl
                        from curriculum_items
                        where curriculum_id=?
                        order by week_number,sort_order
                        limit 200
                        """,
                        curriculumId));
        return result;
    }

    private List<Map<String, Object>> routineItems(Long userId, Map<String, Object> arguments) {
        LocalDate from = date(arguments, "dateFrom", LocalDate.now(ZoneId.of("Asia/Seoul")));
        LocalDate to =
                date(
                        arguments,
                        "dateTo",
                        arguments.containsKey("routineId") ? from.plusDays(30) : from);
        if (to.isBefore(from) || to.isAfter(from.plusDays(30))) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "루틴 조회 범위는 최대 31일입니다.");
        }
        StringBuilder sql =
                new StringBuilder(
                        """
                select routine_id routineId,
                       routine_title routineTitle,
                       routine_goal routineGoal,
                       routine_status routineStatus,
                       ai_adjustment_allowed aiAdjustmentAllowed,
                       routine_item_id routineItemId,
                       section_id sectionId,
                       section_type sectionType,
                       section_title sectionTitle,
                       item_type itemType,
                       item_title itemTitle,
                       content,
                       scheduled_date scheduledDate,
                       scheduled_at scheduledAt,
                       target_value targetValue,
                       target_unit targetUnit,
                       sets_count setsCount,
                       rest_seconds restSeconds,
                       memo,
                       sequence,
                       intensity,
                       item_status itemStatus
                from ai_routine_item_view
                where user_id=?
                  and scheduled_date between ? and ?
                """);
        List<Object> parameters = new ArrayList<>(List.of(userId, from, to));
        if (arguments.containsKey("routineId")) {
            sql.append(" and routine_id=?");
            parameters.add(positiveLong(arguments, "routineId"));
        }
        addExactFilter(sql, parameters, arguments, "itemType", "item_type");
        addExactFilter(sql, parameters, arguments, "sectionType", "section_type");
        addExactFilter(sql, parameters, arguments, "status", "item_status");
        addExactFilter(sql, parameters, arguments, "intensity", "intensity");
        addLikeFilter(sql, parameters, arguments, "sectionKeyword", "section_title");
        if (arguments.containsKey("keyword")) {
            String keyword = requiredFilterValue(arguments.get("keyword"), "keyword");
            sql.append(
                    " and (lower(item_title) like ? or lower(coalesce(content,'')) like ? or lower(coalesce(section_title,'')) like ?)");
            String pattern = "%" + keyword.toLowerCase(Locale.ROOT) + "%";
            parameters.add(pattern);
            parameters.add(pattern);
            parameters.add(pattern);
        }
        sql.append(" order by scheduled_date, sequence limit 200");
        return db.queryForList(sql.toString(), parameters.toArray());
    }

    private void addExactFilter(
            StringBuilder sql,
            List<Object> parameters,
            Map<String, Object> arguments,
            String argumentName,
            String columnName) {
        if (!arguments.containsKey(argumentName)) return;
        String value =
                requiredFilterValue(arguments.get(argumentName), argumentName)
                        .toUpperCase(Locale.ROOT);
        sql.append(" and ").append(columnName).append("=?");
        parameters.add(value);
    }

    private void addLikeFilter(
            StringBuilder sql,
            List<Object> parameters,
            Map<String, Object> arguments,
            String argumentName,
            String columnName) {
        if (!arguments.containsKey(argumentName)) return;
        String value =
                requiredFilterValue(arguments.get(argumentName), argumentName)
                        .toLowerCase(Locale.ROOT);
        sql.append(" and lower(coalesce(").append(columnName).append(",'')) like ?");
        parameters.add("%" + value + "%");
    }

    private Map<String, Object> recentRecords(Long userId, Map<String, Object> arguments) {
        int days = integer(arguments, "days", 28);
        if (days < 1 || days > 90) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "기록 조회 기간은 1~90일입니다.");
        }
        Timestamp from =
                Timestamp.from(Instant.now().minus(days, java.time.temporal.ChronoUnit.DAYS));

        List<Map<String, Object>> activities =
                db.queryForList(
                        """
                        select activity_record_id activityRecordId,
                               routine_item_id routineItemId,
                               record_type recordType,
                               actual_value actualValue,
                               status,
                               details,
                               energy_level energyLevel,
                               pain_level painLevel,
                               condition_memo conditionMemo,
                               performed_at performedAt
                        from ai_activity_record_view
                        where user_id=? and performed_at>=?
                        order by performed_at desc
                        limit 200
                        """,
                        userId,
                        from);

        List<Map<String, Object>> healthMetrics =
                db.queryForList(
                        """
                        select health_record_id healthRecordId,
                               metric_type metricType,
                               metric_value metricValue,
                               unit,
                               input_source inputSource,
                               measured_at measuredAt
                        from ai_health_metric_view
                        where user_id=? and measured_at>=?
                        order by measured_at desc
                        limit 200
                        """,
                        userId,
                        from);

        return Map.of("activities", activities, "healthMetrics", healthMetrics);
    }

    private List<Map<String, Object>> healthMeasurements(
            Long userId, Map<String, Object> arguments) {
        LocalDate from =
                date(arguments, "dateFrom", LocalDate.now(ZoneId.of("Asia/Seoul")).minusDays(90));
        LocalDate to = date(arguments, "dateTo", LocalDate.now(ZoneId.of("Asia/Seoul")));
        if (to.isBefore(from) || to.isAfter(from.plusDays(365)))
            throw new ApiException(HttpStatus.BAD_REQUEST, "건강 측정 조회 범위는 최대 1년입니다.");
        StringBuilder sql =
                new StringBuilder(
                        "select health_measurement_id measurementId,document_id documentId,category,metric_code metricCode,label,body_part bodyPart,body_side bodySide,numeric_value numericValue,text_value textValue,unit,reference_min referenceMin,reference_max referenceMax,measured_at measuredAt,confidence from ai_health_measurement_view where user_id=? and measured_at between ? and ?");
        List<Object> values = new ArrayList<>(List.of(userId, from, to));
        addExactFilter(sql, values, arguments, "metricCode", "metric_code");
        addExactFilter(sql, values, arguments, "category", "category");
        sql.append(" order by measured_at,health_measurement_id limit 300");
        return db.queryForList(sql.toString(), values.toArray());
    }

    private List<Map<String, Object>> healthDocuments(Long userId, Map<String, Object> arguments) {
        StringBuilder sql =
                new StringBuilder(
                        "select document_id documentId,document_type documentType,measured_at measuredAt,processing_status processingStatus,extracted_at extractedAt,created_at createdAt from ai_health_document_view where user_id=?");
        List<Object> values = new ArrayList<>(List.of(userId));
        addExactFilter(sql, values, arguments, "documentType", "document_type");
        addExactFilter(sql, values, arguments, "status", "processing_status");
        sql.append(" order by created_at desc limit 100");
        return db.queryForList(sql.toString(), values.toArray());
    }

    private List<Map<String, Object>> analysisHistory(Long userId, Map<String, Object> arguments) {
        StringBuilder sql =
                new StringBuilder(
                        "select analysis_id analysisId,analysis_type analysisType,summary,status,progress,completed_at completedAt,created_at createdAt from ai_analysis_history_view where user_id=?");
        List<Object> values = new ArrayList<>(List.of(userId));
        addExactFilter(sql, values, arguments, "status", "status");
        sql.append(" order by created_at desc limit 100");
        return db.queryForList(sql.toString(), values.toArray());
    }

    private List<Map<String, Object>> routineProgress(Long userId, Map<String, Object> arguments) {
        LocalDate from =
                date(
                        arguments,
                        "dateFrom",
                        LocalDate.now(ZoneId.of("Asia/Seoul")).with(java.time.DayOfWeek.MONDAY));
        LocalDate to = date(arguments, "dateTo", from.plusDays(6));
        if (to.isBefore(from) || to.isAfter(from.plusDays(90)))
            throw new ApiException(HttpStatus.BAD_REQUEST, "루틴 달성률 조회 범위는 최대 91일입니다.");
        StringBuilder sql =
                new StringBuilder(
                        "select routine_id routineId,routine_title routineTitle,scheduled_date scheduledDate,item_status itemStatus,count(*) itemCount,sum(case when item_status='COMPLETED' then 1 else 0 end) completedCount,sum(case when item_status='SKIPPED' then 1 else 0 end) skippedCount from ai_routine_item_view where user_id=? and scheduled_date between ? and ?");
        List<Object> values = new ArrayList<>(List.of(userId, from, to));
        if (arguments.containsKey("routineId")) {
            sql.append(" and routine_id=?");
            values.add(positiveLong(arguments, "routineId"));
        }
        sql.append(
                " group by routine_id,routine_title,scheduled_date,item_status order by scheduled_date,routine_id limit 300");
        return db.queryForList(sql.toString(), values.toArray());
    }

    private List<Map<String, Object>> activityRecords(Long userId, Map<String, Object> arguments) {
        LocalDate from =
                date(arguments, "dateFrom", LocalDate.now(ZoneId.of("Asia/Seoul")).minusDays(28));
        LocalDate to = date(arguments, "dateTo", LocalDate.now(ZoneId.of("Asia/Seoul")));
        if (to.isBefore(from) || to.isAfter(from.plusDays(90)))
            throw new ApiException(HttpStatus.BAD_REQUEST, "수행 기록 조회 범위는 최대 91일입니다.");
        StringBuilder sql =
                new StringBuilder(
                        "select activity_record_id activityRecordId,routine_item_id routineItemId,record_type recordType,actual_value actualValue,status,details,energy_level energyLevel,pain_level painLevel,condition_memo conditionMemo,performed_at performedAt from ai_activity_record_view where user_id=? and performed_at>=? and performed_at<?");
        List<Object> values =
                new ArrayList<>(
                        List.of(
                                userId,
                                Timestamp.valueOf(from.atStartOfDay()),
                                Timestamp.valueOf(to.plusDays(1).atStartOfDay())));
        addExactFilter(sql, values, arguments, "recordType", "record_type");
        if (arguments.containsKey("minPain")) {
            sql.append(" and pain_level>=?");
            values.add(integer(arguments, "minPain", 0));
        }
        sql.append(" order by performed_at desc limit 300");
        return db.queryForList(sql.toString(), values.toArray());
    }

    private List<Map<String, Object>> typedRecords(
            Long userId, Map<String, Object> arguments, String type) {
        int days = integer(arguments, "days", 7);
        if (days < 1 || days > 90)
            throw new ApiException(HttpStatus.BAD_REQUEST, "기록 조회 기간은 1~90일입니다.");
        return db.queryForList(
                "select activity_record_id activityRecordId,routine_item_id routineItemId,record_type recordType,actual_value actualValue,details,energy_level energyLevel,pain_level painLevel,condition_memo conditionMemo,performed_at performedAt from ai_activity_record_view where user_id=? and record_type=? and performed_at>=? order by performed_at desc limit 300",
                userId,
                type,
                Timestamp.from(Instant.now().minus(days, java.time.temporal.ChronoUnit.DAYS)));
    }

    private List<Map<String, Object>> hydrationRecords(Long userId, Map<String, Object> arguments) {
        int days = integer(arguments, "days", 7);
        if (days < 1 || days > 90)
            throw new ApiException(HttpStatus.BAD_REQUEST, "물 섭취 기록 조회 기간은 1~90일입니다.");
        return db.queryForList(
                "select activity_record_id activityRecordId,details,performed_at performedAt from ai_activity_record_view where user_id=? and record_type='OTHER' and lower(coalesce(details,'')) like ? and performed_at>=? order by performed_at desc limit 300",
                userId,
                "%waterml%",
                Timestamp.from(Instant.now().minus(days, java.time.temporal.ChronoUnit.DAYS)));
    }

    private List<Map<String, Object>> chatHistory(Long userId, Map<String, Object> arguments) {
        int days = integer(arguments, "days", 7);
        if (days < 1 || days > 365)
            throw new ApiException(HttpStatus.BAD_REQUEST, "대화 조회 기간은 1~365일입니다.");
        StringBuilder sql =
                new StringBuilder(
                        "select chat_conversation_id conversationId,conversation_title conversationTitle,chat_message_id messageId,sender_role senderRole,content,response_type responseType,created_at createdAt from ai_chat_history_view where user_id=? and created_at>=?");
        List<Object> values =
                new ArrayList<>(
                        List.of(
                                userId,
                                Timestamp.from(
                                        Instant.now()
                                                .minus(days, java.time.temporal.ChronoUnit.DAYS))));
        if (arguments.containsKey("keyword")) {
            String keyword = requiredFilterValue(arguments.get("keyword"), "keyword");
            sql.append(" and lower(content) like ?");
            values.add("%" + keyword.toLowerCase(Locale.ROOT) + "%");
        }
        sql.append(" order by chat_message_id desc limit 100");
        return db.queryForList(sql.toString(), values.toArray());
    }

    private List<Map<String, Object>> creditHistory(Long userId, Map<String, Object> arguments) {
        int days = integer(arguments, "days", 90);
        if (days < 1 || days > 365)
            throw new ApiException(HttpStatus.BAD_REQUEST, "크레딧 조회 기간은 1~365일입니다.");
        return db.queryForList(
                "select credit_transaction_id creditTransactionId,amount,balance_after balanceAfter,reason,created_at createdAt from ai_credit_history_view where user_id=? and created_at>=? order by created_at desc limit 100",
                userId,
                Timestamp.from(Instant.now().minus(days, java.time.temporal.ChronoUnit.DAYS)));
    }

    private List<Map<String, Object>> notificationSettings(Long userId) {
        return db.queryForList(
                "select routine_reminder_enabled routineReminderEnabled,routine_reminder_time routineReminderTime,marketing_enabled marketingEnabled,updated_at updatedAt from ai_notification_setting_view where user_id=?",
                userId);
    }

    private List<Map<String, Object>> marketProducts(Map<String, Object> arguments) {
        List<String> keywords = marketKeywords(arguments);
        StringBuilder sql =
                new StringBuilder(
                        """
                select market_item_id marketItemId,
                       name,
                       item_type itemType,
                       price,
                       provider_name providerName,
                       image_url imageUrl,
                       purchase_url purchaseUrl
                from ai_market_product_view
                where item_type='MEAL'
                """);
        List<Object> parameters = new ArrayList<>();
        if (!keywords.isEmpty()) {
            sql.append(" and (");
            for (int index = 0; index < keywords.size(); index++) {
                if (index > 0) sql.append(" or ");
                sql.append("lower(name) like ?");
                parameters.add("%" + keywords.get(index).toLowerCase(Locale.ROOT) + "%");
            }
            sql.append(")");
        }
        sql.append(" order by market_item_id desc limit 30");
        return db.queryForList(sql.toString(), parameters.toArray());
    }

    private List<String> marketKeywords(Map<String, Object> arguments) {
        List<String> result = new ArrayList<>();
        Object rawKeywords = arguments.get("keywords");
        if (rawKeywords instanceof Collection<?> values) {
            for (Object value : values) {
                String keyword = sanitizeKeyword(value);
                if (keyword != null && !result.contains(keyword)) result.add(keyword);
                if (result.size() == 10) break;
            }
        }
        if (result.isEmpty()) {
            String keyword = sanitizeKeyword(arguments.get("keyword"));
            if (keyword != null) result.add(keyword);
        }
        return result;
    }

    private String sanitizeKeyword(Object value) {
        if (value == null) return null;
        String keyword = String.valueOf(value).trim().replace("%", "").replace("_", "");
        if (keyword.isBlank()) return null;
        return keyword.length() > 50 ? keyword.substring(0, 50) : keyword;
    }

    private String requiredFilterValue(Object value, String argumentName) {
        String sanitized = sanitizeKeyword(value);
        if (sanitized == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, argumentName + " 값이 비어 있습니다.");
        }
        return sanitized;
    }

    private LocalDate date(Map<String, Object> arguments, String key, LocalDate defaultValue) {
        Object value = arguments.get(key);
        if (value == null) return defaultValue;
        try {
            return LocalDate.parse(String.valueOf(value));
        } catch (Exception exception) {
            throw new ApiException(HttpStatus.BAD_REQUEST, key + " 날짜 형식이 올바르지 않습니다.");
        }
    }

    private int integer(Map<String, Object> arguments, String key, int defaultValue) {
        Object value = arguments.get(key);
        if (value == null) return defaultValue;
        if (value instanceof Number number) return number.intValue();
        try {
            return Integer.parseInt(String.valueOf(value));
        } catch (Exception exception) {
            throw new ApiException(HttpStatus.BAD_REQUEST, key + " 값이 숫자가 아닙니다.");
        }
    }

    private long positiveLong(Map<String, Object> arguments, String key) {
        Object value = arguments.get(key);
        try {
            long parsed = Long.parseLong(String.valueOf(value));
            if (parsed <= 0) throw new NumberFormatException();
            return parsed;
        } catch (Exception exception) {
            throw new ApiException(HttpStatus.BAD_REQUEST, key + " 값이 올바르지 않습니다.");
        }
    }
}
