package tri_lion.health.service.chat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tri_lion.health.domain.health.HealthDocument;
import tri_lion.health.exception.ApiException;
import tri_lion.health.external.storage.ObjectStorage;
import tri_lion.health.security.AuthenticatedUser;
import tri_lion.health.service.health.AnalysisService;

/**
 * Safe, user-owned chatbot actions which do not fit an existing domain service yet. Every read and
 * write is scoped to the JWT user's id inside this class.
 */
@Service
public class ChatbotExpansionService {
    private static final int MAX_BATCH = 50;
    private static final ZoneId KOREA = ZoneId.of("Asia/Seoul");
    private static final Set<String> PROFILE_KEYS =
            Set.of(
                    "targetWeightKg",
                    "goals",
                    "allergies",
                    "injuries",
                    "availableExerciseDays",
                    "dietaryPreferences",
                    "dislikedFoods",
                    "availableExerciseMinutes");

    private final JdbcTemplate db;
    private final AuthenticatedUser auth;
    private final ObjectMapper json;
    private final ObjectStorage storage;
    private final AnalysisService analyses;

    public ChatbotExpansionService(
            JdbcTemplate db,
            AuthenticatedUser auth,
            ObjectMapper json,
            ObjectStorage storage,
            AnalysisService analyses) {
        this.db = db;
        this.auth = auth;
        this.json = json;
        this.storage = storage;
        this.analyses = analyses;
    }

    public void validate(String methodName, Map<String, Object> args) {
        if (args == null) throw new ApiException(HttpStatus.BAD_REQUEST, "AI 변경 인자가 없습니다.");
        Long userId = auth.active().getId();
        switch (methodName) {
            case "chatbotExpansion.patchWellnessProfile" -> validateProfile(args);
            case "chatbotExpansion.batchPatchRoutineItems" ->
                    validateItemBatch(userId, args, "PATCH");
            case "chatbotExpansion.rescheduleRoutineItems" ->
                    validateItemBatch(userId, args, "RESCHEDULE");
            case "chatbotExpansion.shiftRoutineItemTimes" -> validateShiftItems(userId, args);
            case "chatbotExpansion.deleteRoutineItems" -> validateItemBatch(userId, args, "DELETE");
            case "chatbotExpansion.updateActivityRecord" ->
                    validateActivityRecord(userId, args, false);
            case "chatbotExpansion.deleteActivityRecord" ->
                    validateActivityRecord(userId, args, true);
            case "chatbotExpansion.deleteHealthRecord" -> validateHealthRecord(userId, args);
            case "chatbotExpansion.pauseRoutine" -> validatePauseRoutine(userId, args);
            case "chatbotExpansion.resumeRoutine" -> validateResumeRoutine(userId, args);
            case "chatbotExpansion.keepOnlyRoutineActive" -> validateKeepOnlyRoutine(userId, args);
            case "chatbotExpansion.updateNotificationSettings" -> validateNotifications(args);
            case "chatbotExpansion.registerChatImageAndAnalyze" -> validateChatImage(userId, args);
            default ->
                    throw new ApiException(
                            HttpStatus.UNPROCESSABLE_ENTITY, "AI가 지원하지 않는 작업을 제안했습니다.");
        }
    }

    @Transactional
    public String execute(String methodName, Map<String, Object> args) {
        validate(methodName, args);
        return switch (methodName) {
            case "chatbotExpansion.patchWellnessProfile" -> patchProfile(args);
            case "chatbotExpansion.batchPatchRoutineItems" -> patchItems(args);
            case "chatbotExpansion.rescheduleRoutineItems" -> rescheduleItems(args);
            case "chatbotExpansion.shiftRoutineItemTimes" -> shiftItemTimes(args);
            case "chatbotExpansion.deleteRoutineItems" -> deleteItems(args);
            case "chatbotExpansion.updateActivityRecord" -> updateActivityRecord(args);
            case "chatbotExpansion.deleteActivityRecord" -> deleteActivityRecord(args);
            case "chatbotExpansion.deleteHealthRecord" -> deleteHealthRecord(args);
            case "chatbotExpansion.pauseRoutine" -> pauseRoutine(args);
            case "chatbotExpansion.resumeRoutine" -> resumeRoutine(args);
            case "chatbotExpansion.keepOnlyRoutineActive" -> keepOnlyRoutineActive(args);
            case "chatbotExpansion.updateNotificationSettings" -> updateNotifications(args);
            case "chatbotExpansion.registerChatImageAndAnalyze" -> registerChatImage(args);
            default ->
                    throw new ApiException(
                            HttpStatus.UNPROCESSABLE_ENTITY, "AI가 지원하지 않는 작업을 제안했습니다.");
        };
    }

    private void validateProfile(Map<String, Object> args) {
        allowOnly(args, PROFILE_KEYS);
        if (args.isEmpty()) throw new ApiException(HttpStatus.BAD_REQUEST, "변경할 건강 프로필 값이 없습니다.");
        if (args.containsKey("targetWeightKg"))
            decimal(args.get("targetWeightKg"), "targetWeightKg", 20, 500);
        if (args.containsKey("availableExerciseMinutes"))
            integer(args.get("availableExerciseMinutes"), "availableExerciseMinutes", 5, 300);
        stringList(args.get("goals"), "goals", 1, 20, 80);
        stringList(args.get("allergies"), "allergies", 0, 30, 80);
        stringList(args.get("availableExerciseDays"), "availableExerciseDays", 1, 7, 20);
        stringList(args.get("dietaryPreferences"), "dietaryPreferences", 0, 30, 80);
        stringList(args.get("dislikedFoods"), "dislikedFoods", 0, 50, 100);
        if (args.containsKey("injuries")) mapList(args.get("injuries"), "injuries", 0, 30);
    }

    private String patchProfile(Map<String, Object> args) {
        Long userId = auth.active().getId();
        int exists =
                db.queryForObject(
                        "select count(*) from user_health_profiles where user_id=?",
                        Integer.class,
                        userId);
        if (exists == 0) throw ApiException.conflict("온보딩을 먼저 완료한 뒤 건강 정보를 수정할 수 있습니다.");
        try {
            BigDecimal target =
                    args.containsKey("targetWeightKg")
                            ? decimal(args.get("targetWeightKg"), "targetWeightKg", 20, 500)
                            : null;
            Integer minutes =
                    args.containsKey("availableExerciseMinutes")
                            ? integer(
                                    args.get("availableExerciseMinutes"),
                                    "availableExerciseMinutes",
                                    5,
                                    300)
                            : null;
            String goals =
                    args.containsKey("goals")
                            ? json.writeValueAsString(
                                    stringList(args.get("goals"), "goals", 1, 20, 80))
                            : null;
            String allergies =
                    args.containsKey("allergies")
                            ? json.writeValueAsString(
                                    stringList(args.get("allergies"), "allergies", 0, 30, 80))
                            : null;
            String days =
                    args.containsKey("availableExerciseDays")
                            ? json.writeValueAsString(
                                    stringList(
                                            args.get("availableExerciseDays"),
                                            "availableExerciseDays",
                                            1,
                                            7,
                                            20))
                            : null;
            String diet =
                    args.containsKey("dietaryPreferences")
                            ? json.writeValueAsString(
                                    stringList(
                                            args.get("dietaryPreferences"),
                                            "dietaryPreferences",
                                            0,
                                            30,
                                            80))
                            : null;
            String disliked =
                    args.containsKey("dislikedFoods")
                            ? json.writeValueAsString(
                                    stringList(
                                            args.get("dislikedFoods"), "dislikedFoods", 0, 50, 100))
                            : null;
            String injuries =
                    args.containsKey("injuries")
                            ? json.writeValueAsString(
                                    mapList(args.get("injuries"), "injuries", 0, 30))
                            : null;
            db.update(
                    "update user_health_profiles set target_weight_kg=coalesce(?,target_weight_kg), available_exercise_minutes=coalesce(?,available_exercise_minutes), goals=coalesce(?,goals), allergies=coalesce(?,allergies), exercise_days=coalesce(?,exercise_days), dietary_preferences=coalesce(?,dietary_preferences), disliked_foods=coalesce(?,disliked_foods), injuries=coalesce(?,injuries), updated_at=? where user_id=?",
                    target,
                    minutes,
                    goals,
                    allergies,
                    days,
                    diet,
                    disliked,
                    injuries,
                    Instant.now(),
                    userId);
            if (goals != null) {
                List<String> values = stringList(args.get("goals"), "goals", 1, 20, 80);
                db.update(
                        "update users set health_goal=?,updated_at=? where user_id=?",
                        values.getFirst(),
                        Instant.now(),
                        userId);
            }
            return "건강 목표와 개인 설정을 변경했습니다.";
        } catch (ApiException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "건강 프로필을 저장하지 못했습니다.");
        }
    }

    private void validateItemBatch(Long userId, Map<String, Object> args, String kind) {
        Set<String> allowed =
                switch (kind) {
                    case "PATCH" -> Set.of("routineId", "items");
                    case "RESCHEDULE" ->
                            Set.of("routineId", "itemIds", "scheduledDate", "scheduledTime");
                    default -> Set.of("routineId", "itemIds");
                };
        allowOnly(args, allowed);
        long routineId = positive(args.get("routineId"), "routineId");
        ensureRoutineMutable(userId, routineId);
        List<Long> ids;
        Map<Long, Map<String, Object>> patchesByItemId = new HashMap<>();
        if ("PATCH".equals(kind)) {
            List<Map<String, Object>> items = mapList(args.get("items"), "items", 1, MAX_BATCH);
            ids = new ArrayList<>();
            for (Map<String, Object> item : items) {
                allowOnly(
                        item,
                        Set.of(
                                "routineItemId",
                                "title",
                                "content",
                                "targetValue",
                                "targetUnit",
                                "sets",
                                "restSeconds",
                                "memo",
                                "excludeFromAiAdjustment",
                                "intensity"));
                long itemId = positive(item.get("routineItemId"), "routineItemId");
                ids.add(itemId);
                patchesByItemId.put(itemId, item);
                if (item.size() == 1)
                    throw new ApiException(HttpStatus.BAD_REQUEST, "루틴 항목의 변경값이 없습니다.");
                if (item.containsKey("targetValue"))
                    decimal(item.get("targetValue"), "targetValue", 0.1, 100000);
                if (item.containsKey("sets")) integer(item.get("sets"), "sets", 1, 100);
                if (item.containsKey("restSeconds"))
                    integer(item.get("restSeconds"), "restSeconds", 0, 3600);
                if (item.containsKey("targetUnit")) unit(item.get("targetUnit"));
                if (item.containsKey("intensity")) intensity(item.get("intensity"));
                text(item.get("title"), "title", 200, true);
                text(item.get("content"), "content", 5000, true);
                text(item.get("memo"), "memo", 500, true);
            }
        } else {
            ids = longList(args.get("itemIds"), "itemIds", 1, MAX_BATCH);
            if ("RESCHEDULE".equals(kind)) {
                requiredDate(args.get("scheduledDate"), "scheduledDate");
                if (args.containsKey("scheduledTime"))
                    time(args.get("scheduledTime"), "scheduledTime");
            }
        }
        if (new HashSet<>(ids).size() != ids.size())
            throw new ApiException(HttpStatus.BAD_REQUEST, "루틴 항목 ID가 중복되었습니다.");
        for (Long itemId : ids) {
            if ("RESCHEDULE".equals(kind)) ensureReschedulableItem(userId, routineId, itemId);
            else {
                PendingRoutineItem current = ensurePendingItem(userId, routineId, itemId);
                if ("PATCH".equals(kind))
                    validateMealPatch(current.itemType(), patchesByItemId.get(itemId));
            }
        }
    }

    private String patchItems(Map<String, Object> args) {
        Long userId = auth.active().getId();
        long routineId = positive(args.get("routineId"), "routineId");
        List<Map<String, Object>> items = mapList(args.get("items"), "items", 1, MAX_BATCH);
        for (Map<String, Object> item : items) {
            long itemId = positive(item.get("routineItemId"), "routineItemId");
            PendingRoutineItem current = ensurePendingItem(userId, routineId, itemId);
            String title = nullableText(item.get("title"));
            String content = nullableText(item.get("content"));
            if ("MEAL".equals(current.itemType())) {
                content =
                        content != null
                                ? normalizeMealContent(content)
                                : renamedMealContent(current, title);
            }
            db.update(
                    "update routine_items set title=coalesce(?,title), content=coalesce(?,content), target_value=coalesce(?,target_value), target_unit=coalesce(?,target_unit), sets_count=coalesce(?,sets_count), rest_seconds=coalesce(?,rest_seconds), memo=coalesce(?,memo), intensity=coalesce(?,intensity), exclude_from_ai_adjustment=coalesce(?,exclude_from_ai_adjustment), edited_by='USER' where routine_item_id=? and personalized_routine_id=? and deleted_at is null",
                    title,
                    content,
                    optionalDecimal(item.get("targetValue")),
                    nullableText(item.get("targetUnit")),
                    optionalInteger(item.get("sets")),
                    optionalInteger(item.get("restSeconds")),
                    nullableText(item.get("memo")),
                    optionalIntensity(item.get("intensity")),
                    optionalBoolean(item.get("excludeFromAiAdjustment")),
                    itemId,
                    routineId);
        }
        return items.size() + "개 루틴 항목을 변경했습니다.";
    }

    private String rescheduleItems(Map<String, Object> args) {
        long routineId = positive(args.get("routineId"), "routineId");
        List<Long> ids = longList(args.get("itemIds"), "itemIds", 1, MAX_BATCH);
        LocalDate date = requiredDate(args.get("scheduledDate"), "scheduledDate");
        LocalTime time =
                args.containsKey("scheduledTime")
                        ? time(args.get("scheduledTime"), "scheduledTime")
                        : null;
        for (Long itemId : ids) {
            if (time == null) {
                db.update(
                        "update routine_items set scheduled_date=?, day_of_week=?, status='PENDING' where routine_item_id=? and personalized_routine_id=?",
                        date,
                        date.getDayOfWeek().name(),
                        itemId,
                        routineId);
            } else {
                db.update(
                        "update routine_items set scheduled_date=?, day_of_week=?, scheduled_at=?, status='PENDING' where routine_item_id=? and personalized_routine_id=?",
                        date,
                        date.getDayOfWeek().name(),
                        Timestamp.from(LocalDateTime.of(date, time).atZone(KOREA).toInstant()),
                        itemId,
                        routineId);
            }
        }
        return ids.size() + "개 루틴 항목의 일정을 변경했습니다.";
    }

    private void validateShiftItems(Long userId, Map<String, Object> args) {
        allowOnly(args, Set.of("routineId", "itemIds", "minuteOffset"));
        long routineId = positive(args.get("routineId"), "routineId");
        ensureRoutineMutable(userId, routineId);
        int minuteOffset = integer(args.get("minuteOffset"), "minuteOffset", -720, 720);
        if (minuteOffset == 0)
            throw new ApiException(HttpStatus.BAD_REQUEST, "minuteOffset은 0일 수 없습니다.");
        List<Long> ids = longList(args.get("itemIds"), "itemIds", 1, MAX_BATCH);
        if (new HashSet<>(ids).size() != ids.size())
            throw new ApiException(HttpStatus.BAD_REQUEST, "루틴 항목 ID가 중복되었습니다.");
        for (Long itemId : ids) ensureReschedulableItem(userId, routineId, itemId);
    }

    private String shiftItemTimes(Map<String, Object> args) {
        long routineId = positive(args.get("routineId"), "routineId");
        int minuteOffset = integer(args.get("minuteOffset"), "minuteOffset", -720, 720);
        List<Long> ids = longList(args.get("itemIds"), "itemIds", 1, MAX_BATCH);
        for (Long itemId : ids) {
            Map<String, Object> row =
                    db.queryForList(
                                    "select scheduled_at,scheduled_date from routine_items where routine_item_id=? and personalized_routine_id=?",
                                    itemId,
                                    routineId)
                            .getFirst();
            LocalDate scheduledDate = LocalDate.parse(String.valueOf(row.get("scheduled_date")));
            Timestamp scheduledAt = (Timestamp) row.get("scheduled_at");
            LocalDateTime base =
                    scheduledAt == null
                            ? scheduledDate.atTime(9, 0)
                            : scheduledAt.toLocalDateTime();
            LocalDateTime shifted = base.plusMinutes(minuteOffset);
            db.update(
                    "update routine_items set scheduled_date=?,day_of_week=?,scheduled_at=?,status='PENDING' where routine_item_id=? and personalized_routine_id=?",
                    shifted.toLocalDate(),
                    shifted.getDayOfWeek().name(),
                    Timestamp.valueOf(shifted),
                    itemId,
                    routineId);
        }
        return ids.size() + "개 루틴 항목의 시간을 " + minuteOffset + "분 이동했습니다.";
    }

    private String deleteItems(Map<String, Object> args) {
        long routineId = positive(args.get("routineId"), "routineId");
        List<Long> ids = longList(args.get("itemIds"), "itemIds", 1, MAX_BATCH);
        for (Long itemId : ids)
            db.update(
                    "update routine_items set deleted_at=? where routine_item_id=? and personalized_routine_id=? and deleted_at is null",
                    Instant.now(),
                    itemId,
                    routineId);
        return ids.size() + "개 루틴 항목을 삭제했습니다.";
    }

    private void validateActivityRecord(Long userId, Map<String, Object> args, boolean deleting) {
        allowOnly(
                args,
                deleting
                        ? Set.of("activityRecordId")
                        : Set.of(
                                "activityRecordId",
                                "details",
                                "energyLevel",
                                "painLevel",
                                "memo",
                                "performedAt"));
        long id = positive(args.get("activityRecordId"), "activityRecordId");
        Integer count =
                db.queryForObject(
                        "select count(*) from activity_records where activity_record_id=? and user_id=?",
                        Integer.class,
                        id,
                        userId);
        if (count == null || count == 0) throw ApiException.notFound("수행 기록을 찾을 수 없습니다.");
        if (!deleting) {
            if (args.size() == 1) throw new ApiException(HttpStatus.BAD_REQUEST, "변경할 기록 값이 없습니다.");
            if (args.containsKey("energyLevel"))
                integer(args.get("energyLevel"), "energyLevel", 1, 5);
            if (args.containsKey("painLevel")) integer(args.get("painLevel"), "painLevel", 0, 5);
            if (args.containsKey("performedAt"))
                OffsetDateTime.parse(String.valueOf(args.get("performedAt")));
            if (args.containsKey("details")) map(args.get("details"), "details");
            text(args.get("memo"), "memo", 500, true);
        }
    }

    private String updateActivityRecord(Map<String, Object> args) {
        long id = positive(args.get("activityRecordId"), "activityRecordId");
        try {
            String details =
                    args.containsKey("details")
                            ? json.writeValueAsString(map(args.get("details"), "details"))
                            : null;
            Instant performed =
                    args.containsKey("performedAt")
                            ? OffsetDateTime.parse(String.valueOf(args.get("performedAt")))
                                    .toInstant()
                            : null;
            db.update(
                    "update activity_records set details=coalesce(?,details), energy_level=coalesce(?,energy_level), pain_level=coalesce(?,pain_level), condition_memo=coalesce(?,condition_memo), performed_at=coalesce(?,performed_at) where activity_record_id=? and user_id=?",
                    details,
                    optionalInteger(args.get("energyLevel")),
                    optionalInteger(args.get("painLevel")),
                    nullableText(args.get("memo")),
                    performed,
                    id,
                    auth.active().getId());
            return "수행 기록을 수정했습니다.";
        } catch (Exception exception) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "수행 기록의 변경값을 저장하지 못했습니다.");
        }
    }

    private String deleteActivityRecord(Map<String, Object> args) {
        long id = positive(args.get("activityRecordId"), "activityRecordId");
        Long userId = auth.active().getId();
        List<Map<String, Object>> found =
                db.queryForList(
                        "select routine_item_id,record_type,performed_at from activity_records where activity_record_id=? and user_id=?",
                        id,
                        userId);
        if (found.isEmpty()) throw ApiException.notFound("수행 기록을 찾을 수 없습니다.");
        Map<String, Object> record = found.getFirst();
        db.update(
                "delete from activity_records where activity_record_id=? and user_id=?",
                id,
                userId);
        Object itemId = record.get("routine_item_id");
        if (itemId != null) {
            Integer completed =
                    db.queryForObject(
                            "select count(*) from activity_records where user_id=? and routine_item_id=? and status='COMPLETED'",
                            Integer.class,
                            userId,
                            itemId);
            if (completed != null && completed == 0)
                db.update(
                        "update routine_items set status='PENDING' where routine_item_id=?",
                        itemId);
        }
        return "수행 기록을 삭제했습니다.";
    }

    private void validateHealthRecord(Long userId, Map<String, Object> args) {
        allowOnly(args, Set.of("healthRecordId"));
        long id = positive(args.get("healthRecordId"), "healthRecordId");
        Integer count =
                db.queryForObject(
                        "select count(*) from health_records where health_record_id=? and user_id=?",
                        Integer.class,
                        id,
                        userId);
        if (count == null || count == 0) throw ApiException.notFound("건강 기록을 찾을 수 없습니다.");
    }

    private String deleteHealthRecord(Map<String, Object> args) {
        Long userId = auth.active().getId();
        long id = positive(args.get("healthRecordId"), "healthRecordId");
        List<Map<String, Object>> target =
                db.queryForList(
                        "select metric_type from health_records where health_record_id=? and user_id=?",
                        id,
                        userId);
        if (target.isEmpty()) throw ApiException.notFound("건강 기록을 찾을 수 없습니다.");
        String metricType = String.valueOf(target.getFirst().get("metric_type"));
        db.update("delete from health_records where health_record_id=? and user_id=?", id, userId);
        if ("WEIGHT".equals(metricType)) {
            List<Map<String, Object>> latest =
                    db.queryForList(
                            "select metric_value from health_records where user_id=? and metric_type='WEIGHT' order by measured_at desc,health_record_id desc limit 1",
                            userId);
            if (!latest.isEmpty())
                db.update(
                        "update user_health_profiles set weight_kg=?,updated_at=? where user_id=?",
                        latest.getFirst().get("metric_value"),
                        Instant.now(),
                        userId);
        }
        return "건강 기록을 삭제했습니다.";
    }

    private void validatePauseRoutine(Long userId, Map<String, Object> args) {
        allowOnly(args, Set.of("routineId", "pausedUntil"));
        long routineId = positive(args.get("routineId"), "routineId");
        ensureRoutineMutable(userId, routineId);
        LocalDate until = requiredDate(args.get("pausedUntil"), "pausedUntil");
        if (until.isBefore(LocalDate.now(KOREA))
                || until.isAfter(LocalDate.now(KOREA).plusDays(90)))
            throw new ApiException(HttpStatus.BAD_REQUEST, "루틴 중지 기간은 오늘부터 90일 이내여야 합니다.");
    }

    private String pauseRoutine(Map<String, Object> args) {
        Long userId = auth.active().getId();
        long routineId = positive(args.get("routineId"), "routineId");
        LocalDate until = requiredDate(args.get("pausedUntil"), "pausedUntil");
        db.update(
                "update personalized_routines set status='PAUSED',paused_until=?,updated_at=? where personalized_routine_id=? and user_id=? and deleted_at is null",
                until,
                Instant.now(),
                routineId,
                userId);
        return "루틴을 " + until + "까지 일시 중지했습니다.";
    }

    private void validateResumeRoutine(Long userId, Map<String, Object> args) {
        allowOnly(args, Set.of("routineId"));
        long routineId = positive(args.get("routineId"), "routineId");
        List<Map<String, Object>> rows =
                db.queryForList(
                        "select status from personalized_routines where personalized_routine_id=? and user_id=? and deleted_at is null",
                        routineId,
                        userId);
        if (rows.isEmpty()) throw ApiException.notFound("루틴을 찾을 수 없습니다.");
        if (!"PAUSED".equals(String.valueOf(rows.getFirst().get("status"))))
            throw ApiException.conflict("중지된 루틴만 다시 시작할 수 있습니다.");
    }

    private String resumeRoutine(Map<String, Object> args) {
        Long userId = auth.active().getId();
        long routineId = positive(args.get("routineId"), "routineId");
        db.update(
                "update personalized_routines set status='ACTIVE',paused_until=null,updated_at=? where personalized_routine_id=? and user_id=? and deleted_at is null",
                Instant.now(),
                routineId,
                userId);
        return "루틴을 다시 시작했습니다.";
    }

    private void validateKeepOnlyRoutine(Long userId, Map<String, Object> args) {
        allowOnly(args, Set.of("routineId"));
        long routineId = positive(args.get("routineId"), "routineId");
        ensureRoutineMutable(userId, routineId);
    }

    private String keepOnlyRoutineActive(Map<String, Object> args) {
        Long userId = auth.active().getId();
        long routineId = positive(args.get("routineId"), "routineId");
        db.update(
                "update personalized_routines set status='PAUSED',paused_until=null,updated_at=? where user_id=? and personalized_routine_id<>? and status='ACTIVE' and deleted_at is null",
                Instant.now(),
                userId,
                routineId);
        db.update(
                "update personalized_routines set status='ACTIVE',paused_until=null,updated_at=? where user_id=? and personalized_routine_id=? and deleted_at is null",
                Instant.now(),
                userId,
                routineId);
        return "선택한 루틴만 활성 상태로 남겼습니다.";
    }

    private void validateNotifications(Map<String, Object> args) {
        allowOnly(
                args, Set.of("routineReminderEnabled", "routineReminderTime", "marketingEnabled"));
        if (args.isEmpty()) throw new ApiException(HttpStatus.BAD_REQUEST, "변경할 알림 설정이 없습니다.");
        if (args.containsKey("routineReminderTime"))
            time(args.get("routineReminderTime"), "routineReminderTime");
        if (args.containsKey("routineReminderEnabled"))
            bool(args.get("routineReminderEnabled"), "routineReminderEnabled");
        if (args.containsKey("marketingEnabled"))
            bool(args.get("marketingEnabled"), "marketingEnabled");
    }

    private String updateNotifications(Map<String, Object> args) {
        Long userId = auth.active().getId();
        Integer exists =
                db.queryForObject(
                        "select count(*) from user_notification_settings where user_id=?",
                        Integer.class,
                        userId);
        Boolean routineEnabled = optionalBoolean(args.get("routineReminderEnabled"));
        LocalTime reminderTime =
                args.containsKey("routineReminderTime")
                        ? time(args.get("routineReminderTime"), "routineReminderTime")
                        : null;
        Boolean marketing = optionalBoolean(args.get("marketingEnabled"));
        if (exists != null && exists > 0) {
            db.update(
                    "update user_notification_settings set routine_reminder_enabled=coalesce(?,routine_reminder_enabled), routine_reminder_time=coalesce(?,routine_reminder_time), marketing_enabled=coalesce(?,marketing_enabled), updated_at=? where user_id=?",
                    routineEnabled,
                    reminderTime,
                    marketing,
                    Instant.now(),
                    userId);
        } else {
            db.update(
                    "insert into user_notification_settings(user_id,routine_reminder_enabled,routine_reminder_time,marketing_enabled,updated_at) values(?,?,?,?,?)",
                    userId,
                    routineEnabled == null || routineEnabled,
                    reminderTime,
                    marketing != null && marketing,
                    Instant.now());
        }
        return "알림 설정을 변경했습니다.";
    }

    private void validateChatImage(Long userId, Map<String, Object> args) {
        allowOnly(args, Set.of("chatMessageId", "documentType", "measuredAt"));
        long messageId = positive(args.get("chatMessageId"), "chatMessageId");
        String type = String.valueOf(args.get("documentType"));
        try {
            HealthDocument.Type.valueOf(type);
        } catch (Exception exception) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "건강 문서 유형이 올바르지 않습니다.");
        }
        if (args.containsKey("measuredAt")) requiredDate(args.get("measuredAt"), "measuredAt");
        Integer count =
                db.queryForObject(
                        "select count(*) from chat_messages m join chat_conversations c on c.chat_conversation_id=m.chat_conversation_id where m.chat_message_id=? and c.user_id=? and c.deleted_at is null and m.has_image=true",
                        Integer.class,
                        messageId,
                        userId);
        if (count == null || count == 0) throw ApiException.notFound("등록할 내 대화 사진을 찾을 수 없습니다.");
    }

    private String registerChatImage(Map<String, Object> args) {
        Long userId = auth.active().getId();
        long messageId = positive(args.get("chatMessageId"), "chatMessageId");
        List<Map<String, Object>> rows =
                db.queryForList(
                        "select m.image_object_key,m.image_content_type from chat_messages m join chat_conversations c on c.chat_conversation_id=m.chat_conversation_id where m.chat_message_id=? and c.user_id=? and c.deleted_at is null",
                        messageId,
                        userId);
        Map<String, Object> row = rows.getFirst();
        String oldKey = String.valueOf(row.get("image_object_key"));
        String contentType = String.valueOf(row.get("image_content_type"));
        byte[] bytes = storage.get(oldKey);
        String newKey = "health/" + userId + "/chat-" + messageId + "-" + UUID.randomUUID();
        storage.put(newKey, bytes, contentType);
        KeyHolder keys = new GeneratedKeyHolder();
        LocalDate measured =
                args.containsKey("measuredAt")
                        ? requiredDate(args.get("measuredAt"), "measuredAt")
                        : null;
        db.update(
                connection -> {
                    var statement =
                            connection.prepareStatement(
                                    "insert into health_documents(user_id,document_type,object_key,original_file_name,content_type,size_bytes,measured_at,processing_status,created_at) values(?,?,?,?,?,?,?,?,?)",
                                    new String[] {"document_id"});
                    statement.setLong(1, userId);
                    statement.setString(2, String.valueOf(args.get("documentType")));
                    statement.setString(3, newKey);
                    statement.setString(4, "chat-image-" + messageId);
                    statement.setString(5, contentType);
                    statement.setLong(6, bytes.length);
                    statement.setObject(7, measured);
                    statement.setString(8, "UPLOADED");
                    statement.setObject(9, Instant.now());
                    return statement;
                },
                keys);
        Long documentId = Objects.requireNonNull(keys.getKey()).longValue();
        var analysis = analyses.create(List.of(documentId), "chat-document-" + messageId);
        return "사진을 건강 문서로 등록하고 분석을 요청했습니다. 분석 ID: " + analysis.getId();
    }

    private void ensureRoutineMutable(Long userId, long routineId) {
        List<Map<String, Object>> rows =
                db.queryForList(
                        "select status from personalized_routines where personalized_routine_id=? and user_id=? and deleted_at is null",
                        routineId,
                        userId);
        if (rows.isEmpty()) throw ApiException.notFound("루틴을 찾을 수 없습니다.");
        if ("COMPLETED".equals(String.valueOf(rows.getFirst().get("status"))))
            throw ApiException.conflict("완료된 루틴은 변경할 수 없습니다.");
    }

    private void ensureReschedulableItem(Long userId, long routineId, long itemId) {
        Integer count =
                db.queryForObject(
                        "select count(*) from routine_items i join personalized_routines r on r.personalized_routine_id=i.personalized_routine_id where i.routine_item_id=? and i.personalized_routine_id=? and r.user_id=? and r.deleted_at is null and i.deleted_at is null and i.status in ('PENDING','SKIPPED')",
                        Integer.class,
                        itemId,
                        routineId,
                        userId);
        if (count == null || count == 0)
            throw ApiException.notFound("일정을 바꿀 수 있는 루틴 항목을 찾을 수 없습니다.");
    }

    private PendingRoutineItem ensurePendingItem(Long userId, long routineId, long itemId) {
        List<Map<String, Object>> rows =
                db.queryForList(
                        "select i.item_type,i.content,i.section_title from routine_items i join personalized_routines r on r.personalized_routine_id=i.personalized_routine_id where i.routine_item_id=? and i.personalized_routine_id=? and r.user_id=? and r.deleted_at is null and i.deleted_at is null and i.status='PENDING'",
                        itemId,
                        routineId,
                        userId);
        if (rows.isEmpty()) throw ApiException.notFound("변경 가능한 루틴 항목을 찾을 수 없습니다.");
        Map<String, Object> row = rows.getFirst();
        return new PendingRoutineItem(
                String.valueOf(row.get("item_type")),
                nullableText(row.get("content")),
                nullableText(row.get("section_title")));
    }

    private void validateMealPatch(String itemType, Map<String, Object> item) {
        if (!"MEAL".equals(itemType)) return;
        String content = nullableText(item.get("content"));
        if (content != null) normalizeMealContent(content);
    }

    private String renamedMealContent(PendingRoutineItem item, String title) {
        if (title == null) return null;
        try {
            JsonNode parsed =
                    item.content() == null
                            ? json.createObjectNode()
                            : json.readTree(item.content());
            ObjectNode meal =
                    parsed instanceof ObjectNode existing
                            ? existing.deepCopy()
                            : json.createObjectNode();
            String mealType = supportedMealType(meal.get("mealType"));
            if (mealType == null) mealType = mealTypeFromSection(item.sectionTitle());

            double calories = numberOrZero(meal, "calories");
            double carbs = numberOrZero(meal, "carbohydrateGrams", "carbs");
            double protein = numberOrZero(meal, "proteinGrams", "protein");
            double fat = numberOrZero(meal, "fatGrams", "fat");
            if (calories == 0 && carbs == 0 && protein == 0 && fat == 0) {
                JsonNode foods = meal.get("foods");
                if (foods != null && foods.isArray()) {
                    for (JsonNode food : foods) {
                        calories += numberOrZero(food, "calories");
                        carbs += numberOrZero(food, "carbs", "carbohydrateGrams");
                        protein += numberOrZero(food, "protein", "proteinGrams");
                        fat += numberOrZero(food, "fat", "fatGrams");
                    }
                }
            }
            meal.put("mealType", mealType);
            ArrayNode foods = meal.putArray("foods");
            foods.addObject()
                    .put("name", title)
                    .put("calories", calories)
                    .put("carbs", carbs)
                    .put("protein", protein)
                    .put("fat", fat);
            meal.put("calories", calories);
            meal.put("carbohydrateGrams", carbs);
            meal.put("proteinGrams", protein);
            meal.put("fatGrams", fat);
            return json.writeValueAsString(meal);
        } catch (Exception exception) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "기존 식단 내용을 읽어 자동으로 변경하지 못했습니다.");
        }
    }

    private String normalizeMealContent(String content) {
        try {
            JsonNode parsed = json.readTree(content);
            if (!(parsed instanceof ObjectNode meal)) throw invalidMealContent();
            if (supportedMealType(meal.get("mealType")) == null) {
                throw invalidMealContent();
            }
            JsonNode foods = meal.get("foods");
            if (foods == null || !foods.isArray() || foods.isEmpty()) throw invalidMealContent();

            double calories = 0;
            double carbs = 0;
            double protein = 0;
            double fat = 0;
            for (JsonNode food : foods) {
                JsonNode name = food.get("name");
                if (!food.isObject()
                        || name == null
                        || !name.isTextual()
                        || name.asText().isBlank()) {
                    throw invalidMealContent();
                }
                calories += nonNegativeFoodNumber(food, "calories");
                carbs += nonNegativeFoodNumber(food, "carbs");
                protein += nonNegativeFoodNumber(food, "protein");
                fat += nonNegativeFoodNumber(food, "fat");
            }
            meal.put("calories", calories);
            meal.put("carbohydrateGrams", carbs);
            meal.put("proteinGrams", protein);
            meal.put("fatGrams", fat);
            return json.writeValueAsString(meal);
        } catch (ApiException exception) {
            throw exception;
        } catch (Exception exception) {
            throw invalidMealContent();
        }
    }

    private double nonNegativeFoodNumber(JsonNode food, String field) {
        JsonNode value = food.get(field);
        if (value == null || !value.isNumber() || value.asDouble() < 0) throw invalidMealContent();
        return value.asDouble();
    }

    private String supportedMealType(JsonNode value) {
        if (value == null || !value.isTextual()) return null;
        return Set.of("BREAKFAST", "LUNCH", "DINNER", "SNACK").contains(value.asText())
                ? value.asText()
                : null;
    }

    private String mealTypeFromSection(String sectionTitle) {
        if (sectionTitle != null && sectionTitle.contains("아침")) return "BREAKFAST";
        if (sectionTitle != null && sectionTitle.contains("점심")) return "LUNCH";
        if (sectionTitle != null && sectionTitle.contains("저녁")) return "DINNER";
        return "SNACK";
    }

    private double numberOrZero(JsonNode value, String... fields) {
        for (String field : fields) {
            JsonNode number = value.get(field);
            if (number != null && number.isNumber() && number.asDouble() >= 0)
                return number.asDouble();
        }
        return 0;
    }

    private record PendingRoutineItem(String itemType, String content, String sectionTitle) {}

    private ApiException invalidMealContent() {
        return new ApiException(
                HttpStatus.BAD_REQUEST,
                "식단 content에는 mealType, foods와 음식별 calories·carbs·protein·fat 값이 필요합니다.");
    }

    private void allowOnly(Map<String, Object> values, Set<String> allowed) {
        if (!allowed.containsAll(values.keySet()))
            throw new ApiException(HttpStatus.BAD_REQUEST, "AI 변경 인자에 허용되지 않은 값이 있습니다.");
    }

    private long positive(Object value, String label) {
        try {
            long result = Long.parseLong(String.valueOf(value));
            if (result <= 0) throw new NumberFormatException();
            return result;
        } catch (Exception e) {
            throw new ApiException(HttpStatus.BAD_REQUEST, label + " 값이 올바르지 않습니다.");
        }
    }

    private BigDecimal decimal(Object value, String label, double min, double max) {
        try {
            BigDecimal result = new BigDecimal(String.valueOf(value));
            if (result.compareTo(BigDecimal.valueOf(min)) < 0
                    || result.compareTo(BigDecimal.valueOf(max)) > 0)
                throw new NumberFormatException();
            return result;
        } catch (Exception e) {
            throw new ApiException(HttpStatus.BAD_REQUEST, label + " 값의 범위가 올바르지 않습니다.");
        }
    }

    private BigDecimal optionalDecimal(Object value) {
        return value == null ? null : decimal(value, "값", 0.1, 100000);
    }

    private int integer(Object value, String label, int min, int max) {
        try {
            int result = Integer.parseInt(String.valueOf(value));
            if (result < min || result > max) throw new NumberFormatException();
            return result;
        } catch (Exception e) {
            throw new ApiException(HttpStatus.BAD_REQUEST, label + " 값의 범위가 올바르지 않습니다.");
        }
    }

    private Integer optionalInteger(Object value) {
        return value == null ? null : integer(value, "값", Integer.MIN_VALUE, Integer.MAX_VALUE);
    }

    private boolean bool(Object value, String label) {
        if (value instanceof Boolean result) return result;
        if ("true".equalsIgnoreCase(String.valueOf(value))) return true;
        if ("false".equalsIgnoreCase(String.valueOf(value))) return false;
        throw new ApiException(HttpStatus.BAD_REQUEST, label + " 값은 true 또는 false여야 합니다.");
    }

    private Boolean optionalBoolean(Object value) {
        return value == null ? null : bool(value, "값");
    }

    private String nullableText(Object value) {
        return value == null ? null : String.valueOf(value).trim();
    }

    private void text(Object value, String label, int max, boolean nullable) {
        if (value == null && nullable) return;
        String text = nullableText(value);
        if (text == null || text.isBlank() || text.length() > max)
            throw new ApiException(HttpStatus.BAD_REQUEST, label + " 값이 올바르지 않습니다.");
    }

    private String unit(Object value) {
        String unit = String.valueOf(value).toUpperCase(Locale.ROOT);
        if (!Set.of("SECONDS", "MINUTES", "REPETITIONS", "METERS", "KILOMETERS", "KCAL")
                .contains(unit))
            throw new ApiException(HttpStatus.BAD_REQUEST, "목표 단위가 올바르지 않습니다.");
        return unit;
    }

    private String intensity(Object value) {
        String result = String.valueOf(value).trim().toUpperCase(Locale.ROOT);
        if (!Set.of("LOW", "MODERATE", "HIGH").contains(result))
            throw new ApiException(
                    HttpStatus.BAD_REQUEST, "운동 강도는 LOW, MODERATE, HIGH 중 하나여야 합니다.");
        return result;
    }

    private String optionalIntensity(Object value) {
        return value == null ? null : intensity(value);
    }

    private LocalDate requiredDate(Object value, String label) {
        try {
            return LocalDate.parse(String.valueOf(value));
        } catch (Exception e) {
            throw new ApiException(HttpStatus.BAD_REQUEST, label + " 날짜 형식이 올바르지 않습니다.");
        }
    }

    private LocalTime time(Object value, String label) {
        try {
            return LocalTime.parse(String.valueOf(value));
        } catch (Exception e) {
            throw new ApiException(HttpStatus.BAD_REQUEST, label + " 시간 형식이 올바르지 않습니다.");
        }
    }

    private Map<String, Object> map(Object value, String label) {
        if (!(value instanceof Map<?, ?> raw))
            throw new ApiException(HttpStatus.BAD_REQUEST, label + " 값이 객체가 아닙니다.");
        Map<String, Object> result = new LinkedHashMap<>();
        raw.forEach((key, item) -> result.put(String.valueOf(key), item));
        return result;
    }

    private List<Map<String, Object>> mapList(Object value, String label, int min, int max) {
        if (!(value instanceof Collection<?> collection)
                || collection.size() < min
                || collection.size() > max)
            throw new ApiException(HttpStatus.BAD_REQUEST, label + " 개수가 올바르지 않습니다.");
        return collection.stream().map(item -> map(item, label)).toList();
    }

    private List<Long> longList(Object value, String label, int min, int max) {
        if (!(value instanceof Collection<?> values) || values.size() < min || values.size() > max)
            throw new ApiException(HttpStatus.BAD_REQUEST, label + " 개수가 올바르지 않습니다.");
        return values.stream().map(item -> positive(item, label)).toList();
    }

    private List<String> stringList(Object value, String label, int min, int max, int textMax) {
        if (value == null && min == 0) return List.of();
        if (!(value instanceof Collection<?> values) || values.size() < min || values.size() > max)
            throw new ApiException(HttpStatus.BAD_REQUEST, label + " 개수가 올바르지 않습니다.");
        List<String> result = new ArrayList<>();
        for (Object item : values) {
            String text = nullableText(item);
            if (text == null || text.isBlank() || text.length() > textMax)
                throw new ApiException(HttpStatus.BAD_REQUEST, label + " 항목이 올바르지 않습니다.");
            if (!result.contains(text)) result.add(text);
        }
        return result;
    }
}
