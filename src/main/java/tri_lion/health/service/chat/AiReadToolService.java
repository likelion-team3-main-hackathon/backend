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
                    "search_market_products");
    private static final Map<String, Set<String>> ALLOWED_ARGUMENTS =
            Map.of(
                    "get_health_summary", Set.of(),
                    "get_latest_analysis", Set.of(),
                    "get_routine_items",
                            Set.of(
                                    "dateFrom",
                                    "dateTo",
                                    "routineId",
                                    "itemType",
                                    "sectionType",
                                    "sectionKeyword",
                                    "status",
                                    "keyword"),
                    "get_recent_records", Set.of("days"),
                    "get_active_curricula", Set.of(),
                    "get_curriculum_detail", Set.of("curriculumId"),
                    "search_market_products", Set.of("keyword", "keywords"));

    private final JdbcTemplate db;

    public AiReadToolService(JdbcTemplate db) {
        this.db = db;
    }

    @Transactional(readOnly = true)
    public List<LookupResult> execute(Long userId, List<LookupRequest> requests) {
        if (requests == null || requests.isEmpty()) return List.of();
        if (requests.size() > 3) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "한 질문에서 조회는 최대 3개까지 가능합니다.");
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
