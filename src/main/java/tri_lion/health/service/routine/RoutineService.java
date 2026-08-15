package tri_lion.health.service.routine;

import com.fasterxml.jackson.databind.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import org.springframework.data.domain.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tri_lion.health.domain.health.*;
import tri_lion.health.domain.record.ActivityType;
import tri_lion.health.domain.routine.*;
import tri_lion.health.dto.request.routine.RoutineRequests;
import tri_lion.health.exception.ApiException;
import tri_lion.health.repository.health.HealthRepositories;
import tri_lion.health.repository.routine.RoutineRepositories;
import tri_lion.health.security.AuthenticatedUser;

@Service
public class RoutineService {
    private final RoutineRepositories.Routines routines;
    private final RoutineRepositories.Items items;
    private final HealthRepositories.Analyses analyses;
    private final HealthRepositories.Jobs jobs;
    private final AuthenticatedUser auth;
    private final ObjectMapper json;
    private final JdbcTemplate db;

    public RoutineService(
            RoutineRepositories.Routines r,
            RoutineRepositories.Items i,
            HealthRepositories.Analyses a,
            HealthRepositories.Jobs j,
            AuthenticatedUser u,
            ObjectMapper o,
            JdbcTemplate db) {
        routines = r;
        items = i;
        analyses = a;
        jobs = j;
        auth = u;
        json = o;
        this.db = db;
    }

    @Transactional
    public AiJob request(RoutineRequests.GenerationRequest req, String key) {
        Long uid = auth.sensitive().getId();
        if (req.mealCountPerDay() == 0 && req.exerciseDaysPerWeek() == 0)
            throw new ApiException(HttpStatus.BAD_REQUEST, "식단 또는 운동 일정 중 하나 이상을 선택해야 합니다.");
        Analysis a =
                analyses.findByIdAndUserId(req.analysisId(), uid)
                        .filter(x -> x.getStatus() == Analysis.Status.COMPLETED)
                        .orElseThrow(
                                () ->
                                        new ApiException(
                                                HttpStatus.UNPROCESSABLE_ENTITY,
                                                "루틴 생성에 필요한 온보딩 또는 건강 분석 정보가 부족합니다."));
        if (key != null) {
            var old =
                    jobs.findByUserIdAndTypeAndIdempotencyKey(
                            uid, AiJob.Type.ROUTINE_GENERATION, key);
            if (old.isPresent()) return old.get();
        }
        try {
            return jobs.save(
                    new AiJob(
                            uid,
                            AiJob.Type.ROUTINE_GENERATION,
                            json.writeValueAsString(req),
                            null,
                            key));
        } catch (Exception e) {
            throw ApiException.conflict("같은 생성 요청이 이미 처리되었습니다.");
        }
    }

    @Transactional
    public Long generate(AiJob job) {
        try {
            JsonNode n = json.readTree(job.getRequestJson());
            LocalDate start = LocalDate.parse(n.get("startDate").asText());
            int weeks = n.get("durationWeeks").asInt();
            int mealCount = Math.max(0, n.path("mealCountPerDay").asInt(3));
            int exerciseDays = Math.max(0, n.path("exerciseDaysPerWeek").asInt(3));
            String movementType = preferredMovementType(n.path("preferredExerciseTypes"));
            Long previous =
                    n.hasNonNull("previousRoutineId") ? n.get("previousRoutineId").asLong() : null;
            Routine.Type routineType =
                    mealCount > 0 && exerciseDays > 0
                            ? Routine.Type.MIXED
                            : mealCount > 0 ? Routine.Type.MEAL : Routine.Type.EXERCISE;
            String title =
                    previous != null
                            ? "재조정 맞춤 루틴"
                            : routineType == Routine.Type.MIXED
                                    ? "맞춤 웰니스 종합 루틴"
                                    : routineType == Routine.Type.MEAL ? "맞춤 식단 루틴" : "맞춤 운동 루틴";
            Routine r =
                    routines.save(
                            new Routine(
                                    job.getUserId(), title, start, weeks, previous, routineType));
            int totalDays = weeks * 7;
            for (int dayIndex = 0; dayIndex < totalDays; dayIndex++) {
                LocalDate date = start.plusDays(dayIndex);
                int week = dayIndex / 7 + 1;
                if (mealCount > 0) saveMealDay(r, date, week, dayIndex, mealCount);
                if (dayIndex % 7 < exerciseDays)
                    saveExerciseDay(r, date, week, dayIndex, movementType);
            }
            if (previous != null) {
                for (ExerciseItem protectedItem :
                        items.findByRoutineIdAndDeletedAtIsNullOrderBySortOrder(previous).stream()
                                .filter(ExerciseItem::isExcludeFromAiAdjustment)
                                .toList()) {
                    long sectionId = r.getId() * 1_000_000 + 90;
                    items.save(
                            new ExerciseItem(
                                    r.getId(),
                                    sectionId,
                                    1,
                                    start,
                                    protectedItem.getEstimatedMinutes(),
                                    "USER_PROTECTED",
                                    "사용자 고정 항목",
                                    90,
                                    protectedItem.getItemType(),
                                    protectedItem.getName(),
                                    protectedItem.getContent(),
                                    protectedItem.getScheduledAt(),
                                    protectedItem.getSortOrder(),
                                    protectedItem.getTargetValue(),
                                    protectedItem.getTargetUnit(),
                                    protectedItem.getSets(),
                                    protectedItem.getRestSeconds(),
                                    protectedItem.getVideoUrl(),
                                    protectedItem.getThumbnailUrl(),
                                    protectedItem.getMemo(),
                                    true,
                                    protectedItem.getEditedBy()));
                }
            }
            job.result(r.getId());
            return r.getId();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private String preferredMovementType(JsonNode preferredTypes) {
        if (preferredTypes.isArray()) {
            for (JsonNode type : preferredTypes)
                if ("REHABILITATION".equals(type.asText())) return "REHABILITATION";
        }
        return "EXERCISE";
    }

    private void saveExerciseDay(
            Routine routine, LocalDate date, int week, int dayIndex, String movementType) {
        String[][] exercises = {
            {"점핑잭", "20", "SECONDS"},
            {"복부 크런치", "15", "REPETITIONS"},
            {"크로스오버 크런치", "16", "REPETITIONS"},
            {"러시안 트위스트", "20", "REPETITIONS"},
            {"마운틴 클라이머", "26", "REPETITIONS"},
            {"다리 들어올리기", "12", "REPETITIONS"},
            {"플랭크", "30", "SECONDS"},
            {"비스듬한 사선 트위스트", "16", "REPETITIONS"},
            {"크로스 암 크런치", "15", "REPETITIONS"},
            {"죽은 곤충 자세", "20", "REPETITIONS"},
            {"마운틴 클라이머", "26", "REPETITIONS"},
            {"레그 스프레드", "10", "REPETITIONS"},
            {"플랭크", "30", "SECONDS"},
            {"고양이 소 포즈", "30", "SECONDS"},
            {"코브라 스트레칭", "30", "SECONDS"},
            {"어린이 포즈", "30", "SECONDS"}
        };
        long sectionBase = routine.getId() * 1_000_000 + (long) dayIndex * 100;
        Instant scheduledAt = date.atTime(18, 0).atZone(ZoneId.of("Asia/Seoul")).toInstant();
        for (int index = 0; index < exercises.length; index++) {
            boolean main = index < 11;
            items.save(
                    new ExerciseItem(
                            routine.getId(),
                            sectionBase + (main ? 10 : 11),
                            week,
                            date,
                            18,
                            main ? "MAIN_EXERCISE" : "COOL_DOWN",
                            main
                                    ? ("REHABILITATION".equals(movementType) ? "재활 운동" : "본 운동")
                                    : "마무리 스트레칭",
                            main ? 10 : 11,
                            movementType,
                            exercises[index][0],
                            null,
                            scheduledAt,
                            main ? index + 1 : index - 10,
                            new BigDecimal(exercises[index][1]),
                            ExerciseItem.Unit.valueOf(exercises[index][2]),
                            1,
                            index == 10 ? 30 : (main ? 10 : 5),
                            "https://cdn.example.com/exercises/" + (index + 1) + ".mp4",
                            null,
                            null,
                            false,
                            Routine.Editor.AI));
        }
    }

    private void saveMealDay(Routine routine, LocalDate date, int week, int dayIndex, int mealCount)
            throws com.fasterxml.jackson.core.JsonProcessingException {
        String[][] meals = {
            {"BREAKFAST", "아침", "그릭요거트볼", "420", "38", "26", "12", "7"},
            {"LUNCH", "점심", "현미밥과 닭가슴살", "610", "82", "41", "18", "12"},
            {"DINNER", "저녁", "연어구이와 샐러드", "510", "32", "41", "18", "18"},
            {"SNACK", "간식", "견과류와 과일", "220", "25", "8", "10", "15"},
            {"SNACK", "간식", "단백질 요거트", "180", "18", "20", "4", "20"},
            {"SNACK", "간식", "바나나", "110", "28", "1", "0", "21"}
        };
        long sectionBase = routine.getId() * 1_000_000 + (long) dayIndex * 100;
        for (int index = 0; index < Math.min(mealCount, meals.length); index++) {
            String[] meal = meals[index];
            Map<String, Object> details =
                    Map.of(
                            "mealType", meal[0],
                            "foods",
                                    List.of(
                                            Map.of(
                                                    "name",
                                                    meal[2],
                                                    "calories",
                                                    Integer.parseInt(meal[3]),
                                                    "carbs",
                                                    Integer.parseInt(meal[4]),
                                                    "protein",
                                                    Integer.parseInt(meal[5]),
                                                    "fat",
                                                    Integer.parseInt(meal[6]))),
                            "calories", Integer.parseInt(meal[3]),
                            "carbohydrateGrams", Integer.parseInt(meal[4]),
                            "proteinGrams", Integer.parseInt(meal[5]),
                            "fatGrams", Integer.parseInt(meal[6]));
            items.save(
                    new ExerciseItem(
                            routine.getId(),
                            sectionBase + index + 1,
                            week,
                            date,
                            15,
                            meal[0],
                            meal[1] + " 식단",
                            index + 1,
                            "MEAL",
                            meal[2],
                            json.writeValueAsString(details),
                            date.atTime(Integer.parseInt(meal[7]), 0)
                                    .atZone(ZoneId.of("Asia/Seoul"))
                                    .toInstant(),
                            1,
                            new BigDecimal(meal[3]),
                            ExerciseItem.Unit.KCAL,
                            1,
                            0,
                            null,
                            null,
                            null,
                            false,
                            Routine.Editor.AI));
        }
    }

    @Transactional(readOnly = true)
    public void validateChatGeneration(RoutineRequests.ChatGenerationRequest request) {
        Long userId = auth.active().getId();
        completedAnalysis(request.analysisId(), userId);
        validateGeneratedItems(request);
    }

    @Transactional
    public Routine createGeneratedRoutine(RoutineRequests.ChatGenerationRequest request) {
        Long userId = auth.active().getId();
        completedAnalysis(request.analysisId(), userId);
        validateGeneratedItems(request);

        boolean hasMeal = request.items().stream().anyMatch(item -> "MEAL".equals(item.itemType()));
        boolean hasMovement =
                request.items().stream().anyMatch(item -> !"MEAL".equals(item.itemType()));
        Routine.Type type =
                hasMeal && hasMovement
                        ? Routine.Type.MIXED
                        : hasMeal ? Routine.Type.MEAL : Routine.Type.EXERCISE;
        Routine routine =
                routines.save(
                        new Routine(
                                userId,
                                request.title(),
                                request.startDate(),
                                request.durationWeeks(),
                                null,
                                type));
        routine.applyAiMetadata("AI가 건강 상태와 최근 기록을 반영해 생성한 루틴입니다.", request.goal());

        Map<String, Long> sectionIds = new LinkedHashMap<>();
        Map<String, Integer> sectionOrders = new LinkedHashMap<>();
        Map<String, Integer> sequences = new HashMap<>();
        for (RoutineRequests.GeneratedRoutineItem item : request.items()) {
            LocalDate date = request.startDate().plusDays(item.dayOffset());
            String sectionKey = item.dayOffset() + ":" + item.sectionType();
            long sectionId =
                    sectionIds.computeIfAbsent(
                            sectionKey,
                            ignored ->
                                    routine.getId() * 1_000_000L
                                            + item.dayOffset() * 100L
                                            + sectionIds.size()
                                            + 1L);
            int sectionOrder =
                    sectionOrders.computeIfAbsent(sectionKey, ignored -> sectionOrders.size() + 1);
            int sequence = sequences.merge(sectionKey, 1, Integer::sum);
            int estimatedMinutes =
                    "MINUTES".equals(item.targetUnit()) ? item.targetValue().intValue() : 15;
            Instant scheduledAt =
                    date.atTime(parseTime(item.scheduledTime(), LocalTime.of(18, 0)))
                            .atZone(ZoneId.of("Asia/Seoul"))
                            .toInstant();
            items.save(
                    new ExerciseItem(
                            routine.getId(),
                            sectionId,
                            item.dayOffset() / 7 + 1,
                            date,
                            estimatedMinutes,
                            item.sectionType(),
                            item.sectionTitle(),
                            sectionOrder,
                            item.itemType(),
                            item.title(),
                            item.content(),
                            scheduledAt,
                            sequence,
                            item.targetValue(),
                            ExerciseItem.Unit.valueOf(item.targetUnit()),
                            Optional.ofNullable(item.sets()).orElse(1),
                            Optional.ofNullable(item.restSeconds()).orElse(0),
                            null,
                            null,
                            item.memo(),
                            false,
                            Routine.Editor.AI));
        }
        return routine;
    }

    @Transactional(readOnly = true)
    public void validateCurriculumPersonalization(
            RoutineRequests.CurriculumPersonalizationRequest request) {
        Long userId = auth.active().getId();
        completedAnalysis(request.analysisId(), userId);
        curriculumHeader(userId, request.curriculumId());
        validateCurriculumChanges(request);
    }

    @Transactional
    public Routine personalizeCurriculum(RoutineRequests.CurriculumPersonalizationRequest request) {
        Long userId = auth.active().getId();
        completedAnalysis(request.analysisId(), userId);
        Map<String, Object> header = curriculumHeader(userId, request.curriculumId());
        validateCurriculumChanges(request);

        String curriculumType = String.valueOf(header.get("curriculumType"));
        Routine.Type routineType =
                switch (curriculumType) {
                    case "MEAL" -> Routine.Type.MEAL;
                    case "MIXED" -> Routine.Type.MIXED;
                    default -> Routine.Type.EXERCISE;
                };
        Routine routine =
                routines.save(
                        new Routine(
                                userId,
                                String.valueOf(header.get("title")) + " - 개인 맞춤",
                                request.startDate(),
                                request.durationWeeks(),
                                null,
                                request.curriculumId(),
                                routineType,
                                Routine.Source.EXPERT_CURRICULUM));
        routine.applyAiMetadata("원본 커리큘럼을 유지하고 건강 제약에 맞게 조정한 사용자 전용 루틴입니다.", "커리큘럼 개인화");

        Set<Long> excluded =
                new HashSet<>(Optional.ofNullable(request.excludedItemIds()).orElseGet(List::of));
        Map<Long, RoutineRequests.PersonalizedItem> replacements = new HashMap<>();
        Optional.ofNullable(request.replacementItems())
                .orElseGet(List::of)
                .forEach(item -> replacements.put(item.sourceItemId(), item));
        excluded.addAll(replacements.keySet());

        List<Map<String, Object>> sourceItems = curriculumItems(request.curriculumId());
        int generatedOrder = 0;
        for (Map<String, Object> source : sourceItems) {
            Long sourceId = ((Number) source.get("itemId")).longValue();
            if (!excluded.contains(sourceId)) {
                savePersonalizedCurriculumItem(
                        routine, request.startDate(), source, null, generatedOrder++);
            }
            RoutineRequests.PersonalizedItem replacement = replacements.get(sourceId);
            if (replacement != null) {
                savePersonalizedCurriculumItem(
                        routine, request.startDate(), source, replacement, generatedOrder++);
            }
        }
        return routine;
    }

    private void validateGeneratedItems(RoutineRequests.ChatGenerationRequest request) {
        if (request.title() == null
                || request.title().isBlank()
                || request.title().length() > 200) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "루틴 제목이 필요합니다.");
        }
        if (request.durationWeeks() < 1 || request.durationWeeks() > 12) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "루틴 기간은 1~12주여야 합니다.");
        }
        if (request.goal() != null && request.goal().length() > 500) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "루틴 목표가 너무 깁니다.");
        }
        if (request.items() == null || request.items().isEmpty() || request.items().size() > 200) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "루틴 항목은 1~200개여야 합니다.");
        }
        int maximumOffset = request.durationWeeks() * 7;
        for (RoutineRequests.GeneratedRoutineItem item : request.items()) {
            if (item.dayOffset() < 0 || item.dayOffset() >= maximumOffset) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "루틴 항목 날짜가 기간을 벗어났습니다.");
            }
            enumValue(ActivityType.class, item.itemType(), "루틴 항목 유형");
            enumValue(ExerciseItem.Unit.class, item.targetUnit(), "루틴 목표 단위");
            requireText(item.sectionType(), 40, "루틴 구간 유형");
            requireText(item.sectionTitle(), 100, "루틴 구간 제목");
            if (item.targetValue() == null
                    || item.targetValue().compareTo(BigDecimal.ZERO) <= 0
                    || item.targetValue().compareTo(new BigDecimal("100000")) > 0) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "루틴 목표값은 0보다 크고 100000 이하여야 합니다.");
            }
            if (item.title() == null || item.title().isBlank() || item.title().length() > 200) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "루틴 항목 제목이 올바르지 않습니다.");
            }
            if (item.content() != null && item.content().length() > 5000) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "루틴 항목 설명이 너무 깁니다.");
            }
            if (item.sets() != null && (item.sets() < 1 || item.sets() > 100)) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "운동 세트 수는 1~100이어야 합니다.");
            }
            if (item.restSeconds() != null
                    && (item.restSeconds() < 0 || item.restSeconds() > 3600)) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "휴식 시간은 0~3600초여야 합니다.");
            }
            parseTime(item.scheduledTime(), LocalTime.of(18, 0));
        }
    }

    private void validateCurriculumChanges(
            RoutineRequests.CurriculumPersonalizationRequest request) {
        if (request.durationWeeks() < 1 || request.durationWeeks() > 12) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "개인화 루틴 기간은 1~12주여야 합니다.");
        }
        List<Map<String, Object>> sourceItems = curriculumItems(request.curriculumId());
        Set<Long> sourceIds = new HashSet<>();
        sourceItems.forEach(item -> sourceIds.add(((Number) item.get("itemId")).longValue()));
        Set<Long> excludedIds = new HashSet<>();
        for (Long excluded : Optional.ofNullable(request.excludedItemIds()).orElseGet(List::of)) {
            if (!sourceIds.contains(excluded)) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "제외할 커리큘럼 항목이 존재하지 않습니다.");
            }
            if (!excludedIds.add(excluded)) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "같은 커리큘럼 항목을 중복 지정할 수 없습니다.");
            }
        }
        Set<Long> replacementIds = new HashSet<>();
        for (RoutineRequests.PersonalizedItem replacement :
                Optional.ofNullable(request.replacementItems()).orElseGet(List::of)) {
            if (!sourceIds.contains(replacement.sourceItemId())) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "대체할 커리큘럼 항목이 존재하지 않습니다.");
            }
            if (!replacementIds.add(replacement.sourceItemId())) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "같은 대체 항목을 중복 지정할 수 없습니다.");
            }
            enumValue(ActivityType.class, replacement.activityType(), "대체 항목 유형");
            if (replacement.title() == null
                    || replacement.title().isBlank()
                    || replacement.title().length() > 200) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "대체 항목 제목이 올바르지 않습니다.");
            }
            if (replacement.description() != null && replacement.description().length() > 2000) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "대체 항목 설명이 너무 깁니다.");
            }
            if (replacement.durationMinutes() != null
                    && (replacement.durationMinutes() < 1 || replacement.durationMinutes() > 300)) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "대체 항목 시간은 1~300분이어야 합니다.");
            }
        }
    }

    private Analysis completedAnalysis(Long analysisId, Long userId) {
        return analyses.findByIdAndUserId(analysisId, userId)
                .filter(analysis -> analysis.getStatus() == Analysis.Status.COMPLETED)
                .orElseThrow(
                        () ->
                                new ApiException(
                                        HttpStatus.UNPROCESSABLE_ENTITY, "완료된 건강 분석 정보가 필요합니다."));
    }

    private Map<String, Object> curriculumHeader(Long userId, Long curriculumId) {
        List<Map<String, Object>> rows =
                db.queryForList(
                        "select m.name title,c.curriculum_type curriculumType,c.duration_days durationDays from enrollments e join curricula c on c.market_item_id=e.content_id join market_items m on m.market_item_id=c.market_item_id where e.user_id=? and e.content_id=? and e.status='ACTIVE'",
                        userId,
                        curriculumId);
        if (rows.isEmpty()) {
            throw ApiException.notFound("이용 중인 커리큘럼을 찾을 수 없습니다.");
        }
        return rows.getFirst();
    }

    private List<Map<String, Object>> curriculumItems(Long curriculumId) {
        List<Map<String, Object>> rows =
                db.queryForList(
                        "select curriculum_item_id itemId,week_number weekNumber,sort_order sortOrder,activity_type activityType,title,description,scheduled_time scheduledTime,duration_minutes durationMinutes,details_json detailsJson,media_url mediaUrl from curriculum_items where curriculum_id=? order by week_number,sort_order",
                        curriculumId);
        if (rows.isEmpty()) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "개인화할 커리큘럼 항목이 없습니다.");
        }
        return rows;
    }

    private void savePersonalizedCurriculumItem(
            Routine routine,
            LocalDate startDate,
            Map<String, Object> source,
            RoutineRequests.PersonalizedItem replacement,
            int generatedOrder) {
        int week = ((Number) source.get("weekNumber")).intValue();
        int order = ((Number) source.get("sortOrder")).intValue();
        int dayOffset = (week - 1) * 7 + Math.min(Math.max(order - 1, 0), 6);
        LocalDate date = startDate.plusDays(dayOffset);
        String activityType =
                replacement == null
                        ? String.valueOf(source.get("activityType"))
                        : replacement.activityType();
        String title =
                replacement == null ? String.valueOf(source.get("title")) : replacement.title();
        String description =
                replacement == null
                        ? Objects.toString(source.get("description"), "")
                        : Objects.toString(replacement.description(), "");
        int duration =
                replacement != null && replacement.durationMinutes() != null
                        ? replacement.durationMinutes()
                        : source.get("durationMinutes") instanceof Number value
                                ? value.intValue()
                                : 15;
        String details =
                replacement == null
                        ? Objects.toString(source.get("detailsJson"), description)
                        : writeJson(replacement.details(), description);
        LocalTime time = parseSqlTime(source.get("scheduledTime"), LocalTime.of(18, 0));
        long sectionId = routine.getId() * 1_000_000L + dayOffset * 100L + generatedOrder + 1L;
        BigDecimal target = new BigDecimal(Math.max(duration, 1));
        ExerciseItem.Unit unit =
                "MEAL".equals(activityType) ? ExerciseItem.Unit.KCAL : ExerciseItem.Unit.MINUTES;
        items.save(
                new ExerciseItem(
                        routine.getId(),
                        sectionId,
                        week,
                        date,
                        duration,
                        activityType,
                        "개인화 " + activityType,
                        order,
                        activityType,
                        title,
                        details,
                        date.atTime(time).atZone(ZoneId.of("Asia/Seoul")).toInstant(),
                        order,
                        target,
                        unit,
                        1,
                        0,
                        Objects.toString(source.get("mediaUrl"), null),
                        null,
                        description,
                        false,
                        Routine.Editor.AI));
    }

    private LocalTime parseTime(String value, LocalTime defaultValue) {
        if (value == null || value.isBlank()) return defaultValue;
        try {
            return LocalTime.parse(value);
        } catch (Exception exception) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "예정 시간은 HH:mm 형식이어야 합니다.");
        }
    }

    private LocalTime parseSqlTime(Object value, LocalTime defaultValue) {
        if (value == null) return defaultValue;
        if (value instanceof LocalTime localTime) return localTime;
        if (value instanceof java.sql.Time time) return time.toLocalTime();
        return parseTime(String.valueOf(value), defaultValue);
    }

    private String writeJson(Map<String, Object> value, String fallback) {
        if (value == null || value.isEmpty()) return fallback;
        try {
            return json.writeValueAsString(value);
        } catch (Exception exception) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "개인화 항목 내용을 변환할 수 없습니다.");
        }
    }

    private <T extends Enum<T>> T enumValue(Class<T> type, String value, String label) {
        try {
            return Enum.valueOf(type, value);
        } catch (Exception exception) {
            throw new ApiException(HttpStatus.BAD_REQUEST, label + " 값이 올바르지 않습니다.");
        }
    }

    private void requireText(String value, int maximumLength, String label) {
        if (value == null || value.isBlank() || value.length() > maximumLength) {
            throw new ApiException(HttpStatus.BAD_REQUEST, label + "이(가) 올바르지 않습니다.");
        }
    }

    public AiJob generation(Long id) {
        return jobs.findByIdAndUserId(id, auth.active().getId())
                .filter(
                        j ->
                                j.getType() == AiJob.Type.ROUTINE_GENERATION
                                        || j.getType() == AiJob.Type.ROUTINE_ADJUSTMENT)
                .orElseThrow(() -> ApiException.notFound("루틴 생성 작업을 찾을 수 없습니다."));
    }

    public Page<Routine> list(int page, int size) {
        return routines.findByUserIdAndDeletedAtIsNullOrderByUpdatedAtDesc(
                auth.active().getId(), PageRequest.of(page, Math.min(size, 100)));
    }

    public Routine owned(Long id) {
        return routines.findByIdAndUserIdAndDeletedAtIsNull(id, auth.active().getId())
                .orElseThrow(() -> ApiException.notFound("해당 ID의 루틴을 찾을 수 없습니다."));
    }

    public void requireContentMutable(Routine routine) {
        if (routine.getStatus() == Routine.Status.COMPLETED) {
            throw ApiException.conflict("완료된 루틴은 변경할 수 없습니다.");
        }
    }

    public void requireActive(Routine routine) {
        if (routine.getStatus() != Routine.Status.ACTIVE) {
            throw ApiException.conflict("현재 진행 중인 루틴만 실행하거나 재조정할 수 있습니다.");
        }
    }

    public void requirePendingItem(ExerciseItem item) {
        if (item.getStatus() != ExerciseItem.Status.PENDING) {
            throw ApiException.conflict("이미 완료하거나 건너뛴 루틴 항목입니다.");
        }
    }

    public void requireRecordableItem(ExerciseItem item) {
        if (item.getStatus() == ExerciseItem.Status.COMPLETED) {
            throw ApiException.conflict("이미 완료한 루틴 항목입니다.");
        }
    }

    public void validatePatchState(Routine routine, String requestedStatus) {
        requireContentMutable(routine);
        validateStatusTransition(routine.getStatus(), requestedStatus);
    }

    public Routine today() {
        Long uid = auth.active().getId();
        LocalDate now = LocalDate.now();
        return routines.findFirstByUserIdAndStartDateLessThanEqualAndEndDateGreaterThanEqualAndDeletedAtIsNullOrderByCreatedAtDesc(
                        uid, now, now)
                .orElseThrow(() -> ApiException.notFound("오늘 생성된 루틴이 없습니다."));
    }

    public Detail detail(Long id) {
        Routine r = owned(id);
        List<ExerciseItem> all =
                items
                        .findByRoutineIdAndDeletedAtIsNullOrderByScheduledDateAscSectionOrderAscSortOrderAsc(
                                id);
        Map<LocalDate, List<ExerciseItem>> byDate = new LinkedHashMap<>();
        all.forEach(
                item ->
                        byDate.computeIfAbsent(
                                        item.getScheduledDate(), ignored -> new ArrayList<>())
                                .add(item));
        List<DayView> dayViews =
                byDate.entrySet().stream()
                        .map(entry -> dayView(entry.getKey(), entry.getValue()))
                        .toList();
        return new Detail(r, dayViews);
    }

    @Transactional
    public Routine patch(Long id, RoutineRequests.PatchRoutine req) {
        Routine r = owned(id);
        validatePatchState(r, req.status());
        r.patch(
                req.title(),
                req.description(),
                req.endDate(),
                req.aiAdjustmentAllowed(),
                req.status());
        return r;
    }

    @Transactional
    public ExerciseItem add(Long routineId, Long sectionId, RoutineRequests.ExerciseRequest q) {
        requireContentMutable(owned(routineId));
        ExerciseItem sectionTemplate =
                items.findFirstByRoutineIdAndSectionIdAndDeletedAtIsNull(routineId, sectionId)
                        .orElseThrow(() -> ApiException.notFound("루틴 구간을 찾을 수 없습니다."));
        validateUrl(q.videoUrl());
        List<ExerciseItem> existing =
                items.findByRoutineIdAndSectionIdAndDeletedAtIsNullOrderBySortOrder(
                        routineId, sectionId);
        int order = Math.min(q.order(), existing.size() + 1);
        existing.stream()
                .filter(x -> x.getSortOrder() >= order)
                .forEach(x -> x.order(x.getSortOrder() + 1));
        return items.save(
                new ExerciseItem(
                        routineId,
                        sectionId,
                        sectionTemplate.getWeek(),
                        sectionTemplate.getScheduledDate(),
                        sectionTemplate.getEstimatedMinutes(),
                        sectionTemplate.getSectionType(),
                        sectionTemplate.getSectionTitle(),
                        sectionTemplate.getSectionOrder(),
                        q.name(),
                        order,
                        q.targetValue(),
                        ExerciseItem.Unit.valueOf(q.targetUnit()),
                        q.sets(),
                        q.restSeconds(),
                        q.videoUrl(),
                        q.thumbnailUrl(),
                        q.memo(),
                        q.excludeFromAiAdjustment(),
                        Routine.Editor.USER));
    }

    @Transactional
    public ExerciseItem patchExercise(
            Long routineId, Long exerciseId, RoutineRequests.PatchExercise q) {
        requireContentMutable(owned(routineId));
        ExerciseItem i =
                items.findByIdAndRoutineIdAndDeletedAtIsNull(exerciseId, routineId)
                        .orElseThrow(() -> ApiException.notFound("운동 항목을 찾을 수 없습니다."));
        requirePendingItem(i);
        validateUrl(q.videoUrl());
        i.patch(
                q.name(),
                q.targetValue(),
                q.targetUnit(),
                q.sets(),
                q.restSeconds(),
                q.videoUrl(),
                q.thumbnailUrl(),
                q.memo(),
                q.excludeFromAiAdjustment());
        return i;
    }

    @Transactional
    public void deleteExercise(Long r, Long i) {
        requireContentMutable(owned(r));
        items.findByIdAndRoutineIdAndDeletedAtIsNull(i, r)
                .orElseThrow(() -> ApiException.notFound("운동 항목을 찾을 수 없습니다."))
                .delete();
    }

    @Transactional
    public List<Long> order(Long r, Long s, List<Long> ids) {
        requireContentMutable(owned(r));
        List<ExerciseItem> current =
                items.findByRoutineIdAndSectionIdAndDeletedAtIsNullOrderBySortOrder(r, s);
        Set<Long> expected =
                current.stream()
                        .map(ExerciseItem::getId)
                        .collect(java.util.stream.Collectors.toSet());
        if (ids.size() != expected.size()
                || new HashSet<>(ids).size() != ids.size()
                || !expected.equals(new HashSet<>(ids)))
            throw new ApiException(HttpStatus.BAD_REQUEST, "해당 구간의 모든 운동 ID를 중복 없이 전달해야 합니다.");
        Map<Long, ExerciseItem> map = new HashMap<>();
        current.forEach(x -> map.put(x.getId(), x));
        for (int x = 0; x < ids.size(); x++) map.get(ids.get(x)).order(x + 1);
        return ids;
    }

    @Transactional
    public AiJob adjust(Long routineId, RoutineRequests.AdjustmentRequest req, String key) {
        Routine old = owned(routineId);
        requireActive(old);
        if (!old.isAiAdjustmentAllowed()) throw ApiException.conflict("AI 재조정이 허용되지 않은 루틴입니다.");
        try {
            String payload =
                    json.writeValueAsString(
                            Map.of(
                                    "startDate",
                                    LocalDate.now(),
                                    "durationWeeks",
                                    4,
                                    "mealCountPerDay",
                                    old.getType() == Routine.Type.EXERCISE ? 0 : 3,
                                    "exerciseDaysPerWeek",
                                    old.getType() == Routine.Type.MEAL ? 0 : 3,
                                    "preferredExerciseTypes",
                                    List.of("WALKING"),
                                    "previousRoutineId",
                                    routineId,
                                    "reason",
                                    req.reason(),
                                    "userMessage",
                                    Optional.ofNullable(req.userMessage()).orElse("")));
            return jobs.save(
                    new AiJob(old.getUserId(), AiJob.Type.ROUTINE_ADJUSTMENT, payload, null, key));
        } catch (Exception e) {
            throw ApiException.conflict("같은 재조정 요청이 이미 처리되었습니다.");
        }
    }

    private void validateUrl(String u) {
        if (u != null && !u.isBlank() && !u.startsWith("https://"))
            throw new ApiException(HttpStatus.BAD_REQUEST, "영상 URL은 HTTPS만 허용합니다.");
    }

    private void validateStatusTransition(Routine.Status current, String requestedStatus) {
        if (requestedStatus == null) return;
        Routine.Status next;
        try {
            next = Routine.Status.valueOf(requestedStatus);
        } catch (Exception exception) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "루틴 상태 값이 올바르지 않습니다.");
        }
        Set<Routine.Status> allowed =
                switch (current) {
                    case DRAFT -> Set.of(Routine.Status.DRAFT, Routine.Status.ACTIVE);
                    case ACTIVE ->
                            Set.of(
                                    Routine.Status.ACTIVE,
                                    Routine.Status.PAUSED,
                                    Routine.Status.COMPLETED);
                    case PAUSED ->
                            Set.of(
                                    Routine.Status.PAUSED,
                                    Routine.Status.ACTIVE,
                                    Routine.Status.COMPLETED);
                    case COMPLETED -> Set.of(Routine.Status.COMPLETED);
                };
        if (!allowed.contains(next)) {
            throw ApiException.conflict("현재 루틴 상태에서는 요청한 상태로 변경할 수 없습니다.");
        }
    }

    private DayView dayView(LocalDate date, List<ExerciseItem> dayItems) {
        ExerciseItem first = dayItems.getFirst();
        Map<Long, List<ExerciseItem>> bySection = new LinkedHashMap<>();
        dayItems.forEach(
                item ->
                        bySection
                                .computeIfAbsent(item.getSectionId(), ignored -> new ArrayList<>())
                                .add(item));
        List<SectionView> sectionViews =
                bySection.entrySet().stream()
                        .map(
                                entry -> {
                                    ExerciseItem section = entry.getValue().getFirst();
                                    return new SectionView(
                                            entry.getKey(),
                                            section.getSectionType(),
                                            section.getSectionTitle(),
                                            section.getSectionOrder(),
                                            entry.getValue());
                                })
                        .toList();
        return new DayView(
                first.getId(),
                first.getDayOfWeek(),
                first.getWeek(),
                date,
                first.getEstimatedMinutes(),
                sectionViews);
    }

    public record Detail(Routine routine, List<DayView> days) {}

    public record DayView(
            Long routineDayId,
            String dayOfWeek,
            int week,
            LocalDate scheduledDate,
            Integer estimatedMinutes,
            List<SectionView> sections) {}

    public record SectionView(
            Long sectionId,
            String sectionType,
            String title,
            int order,
            List<ExerciseItem> exercises) {}
}
