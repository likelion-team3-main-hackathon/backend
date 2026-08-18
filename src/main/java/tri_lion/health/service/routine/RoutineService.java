package tri_lion.health.service.routine;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tri_lion.health.domain.health.*;
import tri_lion.health.domain.record.ActivityType;
import tri_lion.health.domain.routine.*;
import tri_lion.health.domain.user.HealthProfile;
import tri_lion.health.dto.request.routine.RoutineRequests;
import tri_lion.health.exception.ApiException;
import tri_lion.health.external.ai.AiClients;
import tri_lion.health.repository.health.HealthRepositories;
import tri_lion.health.repository.routine.RoutineRepositories;
import tri_lion.health.repository.user.UserRepositories;
import tri_lion.health.security.AuthenticatedUser;
import tri_lion.health.service.health.AiJobTransactions;
import tri_lion.health.service.health.AiRequestLimitService;

@Service
public class RoutineService {
    private static final Logger log = LoggerFactory.getLogger(RoutineService.class);
    private final RoutineRepositories.Routines routines;
    private final RoutineRepositories.Items items;
    private final HealthRepositories.Analyses analyses;
    private final HealthRepositories.Jobs jobs;
    private final UserRepositories.Profiles profiles;
    private final AuthenticatedUser auth;
    private final ObjectMapper json;
    private final JdbcTemplate db;
    private final AiClients.LlmClient llm;
    private final AiRequestLimitService limits;

    public RoutineService(
            RoutineRepositories.Routines r,
            RoutineRepositories.Items i,
            HealthRepositories.Analyses a,
            HealthRepositories.Jobs j,
            UserRepositories.Profiles p,
            AuthenticatedUser u,
            ObjectMapper o,
            JdbcTemplate db,
            AiClients.LlmClient llm,
            AiRequestLimitService limits) {
        routines = r;
        items = i;
        analyses = a;
        jobs = j;
        profiles = p;
        auth = u;
        json = o;
        this.db = db;
        this.llm = llm;
        this.limits = limits;
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
        validateRecommendationSelection(req, a);
        Set<Long> allReplacedRoutineIds = new LinkedHashSet<>();
        if (req.replacedMealRoutineIds() != null)
            allReplacedRoutineIds.addAll(req.replacedMealRoutineIds());
        if (req.replacedExerciseRoutineIds() != null)
            allReplacedRoutineIds.addAll(req.replacedExerciseRoutineIds());
        for (Long targetRoutineId : allReplacedRoutineIds) {
            requireActive(owned(targetRoutineId));
        }
        limits.lockJobCreation();
        if (key != null) {
            var old =
                    jobs.findByUserIdAndTypeAndIdempotencyKey(
                            uid, AiJob.Type.ROUTINE_GENERATION, key);
            if (old.isPresent()) return old.get();
        }
        var active =
                jobs.findFirstByUserIdAndTypeAndStatusInOrderByCreatedAtDesc(
                        uid,
                        AiJob.Type.ROUTINE_GENERATION,
                        List.of(
                                AiJob.Status.PENDING,
                                AiJob.Status.PROCESSING,
                                AiJob.Status.RETRYING));
        if (active.isPresent()) return active.get();
        limits.authorizeJob(uid, AiJob.Type.ROUTINE_GENERATION);
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

    public RoutinePlan plan(AiJob job) {
        try {
            JsonNode request = json.readTree(job.getRequestJson());
            int mealCount = Math.max(0, request.path("mealCountPerDay").asInt(3));
            int exerciseDays = Math.max(0, request.path("exerciseDaysPerWeek").asInt(3));
            String movementType = preferredMovementType(request.path("preferredExerciseTypes"));
            return routinePlan(job, request, mealCount, exerciseDays, movementType);
        } catch (Exception exception) {
            throw new IllegalStateException("AI 루틴 계획을 생성하지 못했습니다.", exception);
        }
    }

    @Transactional
    public Long generate(AiJobTransactions.JobSnapshot snapshot, RoutinePlan plan) {
        try {
            AiJob job = snapshot.detachedJob();
            JsonNode n = json.readTree(job.getRequestJson());
            LocalDate start = LocalDate.parse(n.get("startDate").asText());
            int weeks = n.get("durationWeeks").asInt();
            int mealCount = Math.max(0, n.path("mealCountPerDay").asInt(3));
            int exerciseDays = Math.max(0, n.path("exerciseDaysPerWeek").asInt(3));
            Long previous =
                    n.hasNonNull("previousRoutineId") ? n.get("previousRoutineId").asLong() : null;
            List<Long> replacedMealIds = readLongList(n, "replacedMealRoutineIds");
            List<Long> replacedExerciseIds = readLongList(n, "replacedExerciseRoutineIds");
            boolean hasExercisePlan =
                    plan.days().stream().anyMatch(day -> !safeExercises(day).isEmpty());

            // 교체 대상으로 지정된 기존 루틴이 있으면 새 루틴을 따로 만들지 않고 그 루틴 안에서
            // 식단/운동 부분만 새 내용으로 바꾼다(같은 "루틴"으로 유지, 별도 탭이 새로 생기지 않게).
            Routine mealTarget =
                    replacedMealIds.isEmpty()
                            ? null
                            : routines.findById(replacedMealIds.get(0))
                                    .filter(x -> x.getStatus() == Routine.Status.ACTIVE)
                                    .orElse(null);
            Routine exerciseTarget =
                    replacedExerciseIds.isEmpty()
                            ? null
                            : routines.findById(replacedExerciseIds.get(0))
                                    .filter(x -> x.getStatus() == Routine.Status.ACTIVE)
                                    .orElse(null);

            Set<Long> affectedRoutineIds = new LinkedHashSet<>();
            affectedRoutineIds.addAll(replacedMealIds);
            affectedRoutineIds.addAll(replacedExerciseIds);
            Map<Long, Routine> affectedRoutines = new LinkedHashMap<>();
            for (Long targetId : affectedRoutineIds) {
                routines.findById(targetId)
                        .filter(x -> x.getStatus() == Routine.Status.ACTIVE)
                        .ifPresent(
                                target -> {
                                    affectedRoutines.put(targetId, target);
                                    boolean removeMeal = replacedMealIds.contains(targetId);
                                    boolean removeExercise = replacedExerciseIds.contains(targetId);
                                    for (ExerciseItem item :
                                            items.findByRoutineIdAndDeletedAtIsNullOrderBySortOrder(
                                                    targetId)) {
                                        boolean isMeal = "MEAL".equals(item.getItemType());
                                        if ((isMeal && removeMeal) || (!isMeal && removeExercise))
                                            item.delete();
                                    }
                                });
            }

            boolean needsNewRoutineForMeal = mealCount > 0 && mealTarget == null;
            boolean needsNewRoutineForExercise = hasExercisePlan && exerciseTarget == null;
            Routine r = null;
            if (needsNewRoutineForMeal || needsNewRoutineForExercise) {
                Routine.Type routineType =
                        needsNewRoutineForMeal && needsNewRoutineForExercise
                                ? Routine.Type.MIXED
                                : needsNewRoutineForMeal
                                        ? Routine.Type.MEAL
                                        : Routine.Type.EXERCISE;
                String title = previous != null ? "재조정 " + plan.title() : plan.title();
                r =
                        new Routine(
                                job.getUserId(),
                                title,
                                plan.description(),
                                start,
                                weeks,
                                previous,
                                routineType);
                r = routines.save(r);
                r.dailySummaries(
                        mergeDailySummaries(
                                r, plan, needsNewRoutineForMeal, needsNewRoutineForExercise));
            }

            Routine mealDestination = mealTarget != null ? mealTarget : r;
            Routine exerciseDestination = exerciseTarget != null ? exerciseTarget : r;

            List<RoutineDayPlan> plannedDays =
                    plan.days().stream()
                            .sorted(Comparator.comparing(RoutineDayPlan::scheduledDate))
                            .toList();
            for (RoutineDayPlan plannedDay : plannedDays) {
                LocalDate date = plannedDay.scheduledDate();
                int dayIndex =
                        Math.toIntExact(java.time.temporal.ChronoUnit.DAYS.between(start, date));
                int week = dayIndex / 7 + 1;
                if (mealCount > 0)
                    saveMealDay(
                            mealDestination, date, week, dayIndex, mealCount, plannedDay.meals());
                if (!safeExercises(plannedDay).isEmpty())
                    saveExerciseDay(
                            exerciseDestination, date, week, dayIndex, safeExercises(plannedDay));
            }

            LocalDate planEnd = start.plusWeeks(weeks).minusDays(1);
            if (mealTarget != null
                    && exerciseTarget != null
                    && Objects.equals(mealTarget.getId(), exerciseTarget.getId())) {
                mealTarget.dailySummaries(mergeDailySummaries(mealTarget, plan, true, true));
                mealTarget.extendEndDate(planEnd);
            } else {
                if (mealTarget != null) {
                    mealTarget.dailySummaries(mergeDailySummaries(mealTarget, plan, true, false));
                    mealTarget.extendEndDate(planEnd);
                }
                if (exerciseTarget != null) {
                    exerciseTarget.dailySummaries(
                            mergeDailySummaries(exerciseTarget, plan, false, true));
                    exerciseTarget.extendEndDate(planEnd);
                }
            }

            if (previous != null && r != null) {
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

            for (Long targetId : affectedRoutineIds) {
                Routine target = affectedRoutines.get(targetId);
                if (target == null) continue;
                List<ExerciseItem> remainingItems =
                        items.findByRoutineIdAndDeletedAtIsNullOrderBySortOrder(targetId);
                boolean hasMealLeft =
                        remainingItems.stream().anyMatch(item -> "MEAL".equals(item.getItemType()));
                boolean hasExerciseLeft =
                        remainingItems.stream()
                                .anyMatch(item -> !"MEAL".equals(item.getItemType()));
                if (!hasMealLeft && !hasExerciseLeft) target.markReplaced();
                else target.recomputeType(hasMealLeft, hasExerciseLeft);
                routines.save(target);
            }

            Long resultRoutineId =
                    r != null
                            ? r.getId()
                            : mealTarget != null ? mealTarget.getId() : exerciseTarget.getId();
            AiJob persistedJob = jobs.findForUpdateById(snapshot.id()).orElseThrow();
            persistedJob.result(resultRoutineId);
            persistedJob.complete(llm.routineModelVersion(), llm.routinePromptVersion());
            return resultRoutineId;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private List<Long> readLongList(JsonNode n, String field) {
        List<Long> ids = new ArrayList<>();
        if (n.hasNonNull(field)) n.get(field).forEach(idNode -> ids.add(idNode.asLong()));
        return ids;
    }

    private String preferredMovementType(JsonNode preferredTypes) {
        if (preferredTypes.isArray()) {
            for (JsonNode type : preferredTypes)
                if ("REHABILITATION".equals(type.asText())) return "REHABILITATION";
        }
        return "EXERCISE";
    }

    private void validateRecommendationSelection(
            RoutineRequests.GenerationRequest request, Analysis analysis) {
        List<String> selected =
                Optional.ofNullable(request.selectedRecommendationIds()).orElse(List.of());
        if (selected.isEmpty()) return;
        if (new HashSet<>(selected).size() != selected.size())
            throw new IllegalArgumentException("추천 루틴 ID를 중복해서 선택할 수 없습니다.");
        try {
            JsonNode recommendations =
                    json.readTree(analysis.getDetails()).path("routineRecommendations");
            Map<String, JsonNode> byId = new HashMap<>();
            recommendations.forEach(value -> byId.put(value.path("id").asText(), value));
            Set<String> categories = new HashSet<>();
            int expectedWeeks = 0;
            int expectedMeals = 0;
            int expectedExerciseDays = 0;
            for (String id : selected) {
                JsonNode recommendation = byId.get(id);
                if (recommendation == null)
                    throw new IllegalArgumentException("건강 분석에 포함되지 않은 추천 루틴입니다.");
                String category = recommendation.path("category").asText();
                if (!categories.add(category))
                    throw new IllegalArgumentException("식단과 운동 추천은 유형별로 하나만 선택할 수 있습니다.");
                expectedWeeks =
                        Math.max(expectedWeeks, recommendation.path("durationWeeks").asInt());
                if ("MEAL".equals(category))
                    expectedMeals = recommendation.path("mealCountPerDay").asInt();
                else if ("EXERCISE".equals(category))
                    expectedExerciseDays = recommendation.path("exerciseDaysPerWeek").asInt();
                else throw new IllegalArgumentException("추천 루틴 유형이 올바르지 않습니다.");
            }
            if (request.durationWeeks() != expectedWeeks
                    || request.mealCountPerDay() != expectedMeals
                    || request.exerciseDaysPerWeek() != expectedExerciseDays)
                throw new IllegalArgumentException("선택한 추천 루틴의 기간 또는 빈도와 요청이 일치하지 않습니다.");
        } catch (com.fasterxml.jackson.core.JsonProcessingException exception) {
            throw new IllegalArgumentException("추천 루틴 정보를 읽을 수 없습니다.", exception);
        }
    }

    private void saveExerciseDay(
            Routine routine,
            LocalDate date,
            int week,
            int dayIndex,
            List<ExerciseTemplate> exercises) {
        long sectionBase = routine.getId() * 1_000_000 + (long) dayIndex * 100;
        Instant scheduledAt = date.atTime(18, 0).atZone(ZoneId.of("Asia/Seoul")).toInstant();
        Map<String, Integer> sectionOrders =
                Map.of("WARM_UP", 9, "MAIN_EXERCISE", 10, "COOL_DOWN", 11);
        Map<String, Integer> sequences = new HashMap<>();
        int totalMinutes = exercises.stream().mapToInt(ExerciseTemplate::estimatedMinutes).sum();
        for (int index = 0; index < exercises.size(); index++) {
            ExerciseTemplate exercise = exercises.get(index);
            int sectionOrder = sectionOrders.get(exercise.sectionType());
            int sequence = sequences.merge(exercise.sectionType(), 1, Integer::sum);
            ExerciseItem item =
                    new ExerciseItem(
                            routine.getId(),
                            sectionBase + sectionOrder,
                            week,
                            date,
                            totalMinutes,
                            exercise.sectionType(),
                            exercise.sectionTitle(),
                            sectionOrder,
                            exercise.activityType(),
                            exercise.title(),
                            null,
                            scheduledAt,
                            sequence,
                            BigDecimal.valueOf(exercise.targetValue()),
                            ExerciseItem.Unit.valueOf(exercise.targetUnit()),
                            exercise.sets(),
                            exercise.restSeconds(),
                            null,
                            null,
                            null,
                            false,
                            Routine.Editor.AI);
            try {
                item.muscleGroups(json.writeValueAsString(inferMuscleGroups(exercise)));
            } catch (Exception ignored) {
                item.muscleGroups("[]");
            }
            items.save(item);
        }
    }

    private List<String> inferMuscleGroups(ExerciseTemplate exercise) {
        String value = (exercise.sectionTitle() + " " + exercise.title()).toLowerCase();
        if (!"MAIN_EXERCISE".equals(exercise.sectionType())) return List.of("MOBILITY");
        List<String> groups = new ArrayList<>();
        if (value.matches(".*(가슴|체스트|푸시업|벤치).*")) groups.add("CHEST");
        if (value.matches(".*(등|로우|랫풀|풀업).*")) groups.add("BACK");
        if (value.matches(".*(어깨|숄더|레터럴).*")) groups.add("SHOULDERS");
        if (value.matches(".*(이두|삼두|컬|팔).*")) groups.add("ARMS");
        if (value.matches(".*(하체|스쿼트|런지|둔근|힙|레그).*")) groups.add("LEGS");
        if (value.matches(".*(코어|복부|크런치|플랭크|버드독|데드버그).*")) groups.add("CORE");
        return groups.isEmpty() ? List.of("FULL_BODY") : groups;
    }

    private void saveMealDay(
            Routine routine,
            LocalDate date,
            int week,
            int dayIndex,
            int mealCount,
            List<MealTemplate> templates)
            throws com.fasterxml.jackson.core.JsonProcessingException {
        long sectionBase = routine.getId() * 1_000_000 + (long) dayIndex * 100;
        for (int index = 0; index < Math.min(mealCount, templates.size()); index++) {
            MealTemplate meal = templates.get(index);
            Map<String, Object> details =
                    Map.of(
                            "mealType", meal.mealType(),
                            "foods",
                                    List.of(
                                            Map.of(
                                                    "name",
                                                    meal.title(),
                                                    "calories",
                                                    meal.calories(),
                                                    "carbs",
                                                    meal.carbohydrateGrams(),
                                                    "protein",
                                                    meal.proteinGrams(),
                                                    "fat",
                                                    meal.fatGrams())),
                            "calories", meal.calories(),
                            "carbohydrateGrams", meal.carbohydrateGrams(),
                            "proteinGrams", meal.proteinGrams(),
                            "fatGrams", meal.fatGrams());
            items.save(
                    new ExerciseItem(
                            routine.getId(),
                            sectionBase + index + 1,
                            week,
                            date,
                            15,
                            meal.mealType(),
                            mealSectionTitle(meal.mealType()),
                            index + 1,
                            "MEAL",
                            meal.title(),
                            json.writeValueAsString(details),
                            date.atTime(mealHour(meal.mealType(), index), 0)
                                    .atZone(ZoneId.of("Asia/Seoul"))
                                    .toInstant(),
                            1,
                            BigDecimal.valueOf(meal.calories()),
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

    private RoutinePlan routinePlan(
            AiJob job, JsonNode request, int mealCount, int exerciseDays, String movementType)
            throws Exception {
        if (llm.live()) limits.reserveExternalCall(job.getUserId(), job.getType());
        RoutinePlan plan =
                llm.live()
                        ? json.readValue(
                                llm.routineGeneration(routineInput(job, request)),
                                RoutinePlan.class)
                        : defaultPlan(request, mealCount, exerciseDays, movementType);
        validatePlan(plan, request, mealCount, exerciseDays, movementType);
        return plan;
    }

    private String routineInput(AiJob job, JsonNode request) throws Exception {
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("request", request);
        input.putAll(routineDateRange(request));
        Analysis analysis =
                request.hasNonNull("analysisId")
                        ? analyses.findByIdAndUserId(
                                        request.path("analysisId").asLong(), job.getUserId())
                                .orElseThrow()
                        : analyses.findFirstByUserIdAndStatusOrderByCompletedAtDesc(
                                        job.getUserId(), Analysis.Status.COMPLETED)
                                .orElseThrow();
        JsonNode healthAnalysis = json.readTree(analysis.getDetails());
        input.put("healthAnalysis", healthAnalysis);
        JsonNode selectedIds = request.path("selectedRecommendationIds");
        if (selectedIds.isArray() && !selectedIds.isEmpty()) {
            Set<String> selected = new HashSet<>();
            selectedIds.forEach(value -> selected.add(value.asText()));
            List<JsonNode> selectedRecommendations = new ArrayList<>();
            healthAnalysis
                    .path("routineRecommendations")
                    .forEach(
                            value -> {
                                if (selected.contains(value.path("id").asText()))
                                    selectedRecommendations.add(value);
                            });
            input.put("selectedRoutineRecommendations", selectedRecommendations);
        }
        profiles.findById(job.getUserId()).ifPresent(p -> input.put("profile", profileInput(p)));
        if (request.hasNonNull("previousRoutineId")) {
            List<Map<String, Object>> protectedItems =
                    items
                            .findByRoutineIdAndDeletedAtIsNullOrderBySortOrder(
                                    request.path("previousRoutineId").asLong())
                            .stream()
                            .filter(ExerciseItem::isExcludeFromAiAdjustment)
                            .map(
                                    item -> {
                                        Map<String, Object> value = new LinkedHashMap<>();
                                        value.put("activityType", item.getItemType());
                                        value.put("title", item.getName());
                                        value.put("targetValue", item.getTargetValue());
                                        value.put("targetUnit", item.getTargetUnit());
                                        return value;
                                    })
                            .toList();
            input.put("protectedItems", protectedItems);
        }
        return json.writeValueAsString(input);
    }

    static Map<String, Object> routineDateRange(JsonNode request) {
        LocalDate startDate = LocalDate.parse(request.path("startDate").asText());
        int totalDays = Math.multiplyExact(request.path("durationWeeks").asInt(), 7);
        return Map.of(
                "startDate", startDate.toString(),
                "expectedEndDate", startDate.plusDays(totalDays - 1L).toString(),
                "totalDays", totalDays);
    }

    private String mergeDailySummaries(
            Routine target, RoutinePlan plan, boolean includeMeal, boolean includeExercise)
            throws Exception {
        JsonNode existing = parseDailySummaries(target.getDailySummaries());
        ObjectNode root = existing.isObject() ? (ObjectNode) existing : json.createObjectNode();
        for (RoutineDayPlan day : plan.days()) {
            String key = day.scheduledDate().toString();
            ObjectNode entry =
                    root.has(key) && root.get(key).isObject()
                            ? (ObjectNode) root.get(key)
                            : root.putObject(key);
            if (includeMeal)
                entry.put("mealTitle", Optional.ofNullable(day.mealSummaryTitle()).orElse(""));
            else if (!entry.has("mealTitle")) entry.put("mealTitle", "");
            if (includeExercise)
                entry.put(
                        "exerciseTitle",
                        Optional.ofNullable(day.exerciseSummaryTitle()).orElse(""));
            else if (!entry.has("exerciseTitle")) entry.put("exerciseTitle", "");
        }
        return json.writeValueAsString(root);
    }

    private Map<String, Object> profileInput(HealthProfile profile) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("birthDate", profile.getBirthDate());
        result.put("gender", profile.getGender());
        result.put("heightCm", profile.getHeightCm());
        result.put("weightKg", profile.getWeightKg());
        result.put("targetWeightKg", profile.getTargetWeightKg());
        result.put("activityLevel", profile.getActivityLevel());
        result.put("availableExerciseMinutes", profile.getAvailableExerciseMinutes());
        result.put("dietaryPreferences", safeJson(profile.getDietaryPreferences()));
        result.put("allergies", safeJson(profile.getAllergies()));
        result.put("dislikedFoods", safeJson(profile.getDislikedFoods()));
        result.put("goals", safeJson(profile.getGoals()));
        result.put("injuries", safeJson(profile.getInjuries()));
        return result;
    }

    private JsonNode safeJson(String value) {
        try {
            return value == null ? json.createArrayNode() : json.readTree(value);
        } catch (Exception ignored) {
            return json.createArrayNode();
        }
    }

    private void validatePlan(
            RoutinePlan plan,
            JsonNode request,
            int mealCount,
            int exerciseDays,
            String requestedMovementType) {
        if (plan == null
                || plan.title() == null
                || plan.title().isBlank()
                || plan.title().length() > 200
                || plan.description() == null
                || plan.description().isBlank())
            throw new IllegalArgumentException("AI 루틴 제목 또는 설명이 올바르지 않습니다.");
        LocalDate start = LocalDate.parse(request.path("startDate").asText());
        int weeks = request.path("durationWeeks").asInt();
        int totalDays = weeks * 7;
        List<RoutineDayPlan> days = Optional.ofNullable(plan.days()).orElse(List.of());
        if (days.size() != totalDays) {
            List<LocalDate> returnedDates =
                    days.stream()
                            .filter(Objects::nonNull)
                            .map(RoutineDayPlan::scheduledDate)
                            .filter(Objects::nonNull)
                            .sorted()
                            .toList();
            log.warn(
                    "AI routine day count mismatch: expectedDays={}, actualDays={}, expectedStart={}, expectedEnd={}, returnedFirst={}, returnedLast={}",
                    totalDays,
                    days.size(),
                    start,
                    start.plusDays(totalDays - 1L),
                    returnedDates.isEmpty() ? null : returnedDates.getFirst(),
                    returnedDates.isEmpty() ? null : returnedDates.getLast());
            throw new IllegalArgumentException(
                    "AI가 전체 기간의 날짜별 루틴을 반환하지 않았습니다. (기대: "
                            + totalDays
                            + "일, 반환: "
                            + days.size()
                            + "일)");
        }
        Map<LocalDate, RoutineDayPlan> daysByDate = new HashMap<>();
        for (RoutineDayPlan day : days) {
            if (day == null
                    || day.scheduledDate() == null
                    || daysByDate.put(day.scheduledDate(), day) != null)
                throw new IllegalArgumentException("AI 루틴 날짜가 없거나 중복되었습니다.");
        }
        for (int dayIndex = 0; dayIndex < totalDays; dayIndex++) {
            LocalDate expectedDate = start.plusDays(dayIndex);
            RoutineDayPlan day = daysByDate.get(expectedDate);
            if (day == null) throw new IllegalArgumentException("AI 루틴 날짜가 연속적이지 않습니다.");
            validateDay(day, mealCount, requestedMovementType);
        }
        for (int week = 0; week < weeks; week++) {
            int exerciseDayCount = 0;
            for (int offset = 0; offset < 7; offset++) {
                RoutineDayPlan day = daysByDate.get(start.plusDays((long) week * 7 + offset));
                if (!safeExercises(day).isEmpty()) exerciseDayCount++;
            }
            if (exerciseDayCount != exerciseDays)
                throw new IllegalArgumentException("AI의 주당 운동일 수가 요청과 일치하지 않습니다.");
        }
    }

    private void validateDay(RoutineDayPlan day, int mealCount, String requestedMovementType) {
        List<MealTemplate> meals = Optional.ofNullable(day.meals()).orElse(List.of());
        List<ExerciseTemplate> exercises = safeExercises(day);
        if (meals.size() != mealCount)
            throw new IllegalArgumentException("AI의 날짜별 식단 수가 요청과 일치하지 않습니다.");
        validateDaySummary(
                day.mealSummaryTitle(),
                !meals.isEmpty(),
                Set.of("식단", "식단 루틴", "맞춤 식단", "아침 식단", "점심 식단", "저녁 식단"),
                "식단");
        validateDaySummary(
                day.exerciseSummaryTitle(),
                !exercises.isEmpty(),
                Set.of("운동", "운동 루틴", "본 운동", "본운동", "맞춤 운동"),
                "운동");
        for (MealTemplate meal : meals) {
            if (!Set.of("BREAKFAST", "LUNCH", "DINNER", "SNACK").contains(meal.mealType())
                    || meal.title() == null
                    || meal.title().isBlank()
                    || meal.calories() < 50
                    || meal.calories() > 3000
                    || meal.carbohydrateGrams() < 0
                    || meal.carbohydrateGrams() > 500
                    || meal.proteinGrams() < 0
                    || meal.proteinGrams() > 500
                    || meal.fatGrams() < 0
                    || meal.fatGrams() > 500)
                throw new IllegalArgumentException("AI 식단 템플릿 값이 허용 범위를 벗어났습니다.");
        }
        if (exercises.size() > 30) throw new IllegalArgumentException("AI 운동 항목 수가 너무 많습니다.");
        if (!exercises.isEmpty()) {
            Set<String> sections = new HashSet<>();
            exercises.forEach(exercise -> sections.add(exercise.sectionType()));
            if (!sections.containsAll(Set.of("WARM_UP", "MAIN_EXERCISE", "COOL_DOWN")))
                throw new IllegalArgumentException("AI 운동일에 준비·본·마무리 운동이 모두 필요합니다.");
            if ("REHABILITATION".equals(requestedMovementType)
                    && exercises.stream()
                            .noneMatch(
                                    exercise -> "REHABILITATION".equals(exercise.activityType())))
                throw new IllegalArgumentException("재활 루틴에 재활 활동이 포함되지 않았습니다.");
        }
        for (ExerciseTemplate exercise : exercises) {
            if (!Set.of("EXERCISE", "REHABILITATION").contains(exercise.activityType())
                    || !Set.of("WARM_UP", "MAIN_EXERCISE", "COOL_DOWN")
                            .contains(exercise.sectionType())
                    || !Set.of("SECONDS", "MINUTES", "REPETITIONS", "METERS", "KILOMETERS")
                            .contains(exercise.targetUnit())
                    || exercise.title() == null
                    || exercise.title().isBlank()
                    || exercise.targetValue() <= 0
                    || exercise.targetValue() > 10000
                    || exercise.sets() < 1
                    || exercise.sets() > 10
                    || exercise.restSeconds() < 0
                    || exercise.restSeconds() > 600
                    || exercise.estimatedMinutes() < 1
                    || exercise.estimatedMinutes() > 180)
                throw new IllegalArgumentException("AI 운동 프로그램 값이 허용 범위를 벗어났습니다.");
        }
    }

    private void validateDaySummary(
            String title, boolean required, Set<String> genericTitles, String type) {
        if (!required) {
            if (title != null && !title.isBlank())
                throw new IllegalArgumentException("AI가 " + type + " 없는 날에 요약 제목을 반환했습니다.");
            return;
        }
        if (title == null
                || title.isBlank()
                || title.length() > 100
                || genericTitles.contains(title.trim()))
            throw new IllegalArgumentException("AI의 날짜별 " + type + " 요약 제목이 올바르지 않습니다.");
    }

    private List<ExerciseTemplate> safeExercises(RoutineDayPlan day) {
        return Optional.ofNullable(day.exerciseItems()).orElse(List.of());
    }

    private RoutinePlan defaultPlan(
            JsonNode request, int mealCount, int exerciseDays, String movementType) {
        List<MealTemplate> mealCatalog =
                List.of(
                        new MealTemplate("BREAKFAST", "그릭요거트볼", 420, 38, 26, 12),
                        new MealTemplate("LUNCH", "현미밥과 닭가슴살", 610, 82, 41, 18),
                        new MealTemplate("DINNER", "연어구이와 샐러드", 510, 32, 41, 18),
                        new MealTemplate("SNACK", "견과류와 과일", 220, 25, 8, 10),
                        new MealTemplate("SNACK", "단백질 요거트", 180, 18, 20, 4),
                        new MealTemplate("SNACK", "바나나", 110, 28, 1, 0));
        LocalDate start = LocalDate.parse(request.path("startDate").asText());
        int totalDays = request.path("durationWeeks").asInt() * 7;
        List<RoutineDayPlan> days = new ArrayList<>();
        int exerciseSession = 0;
        for (int dayIndex = 0; dayIndex < totalDays; dayIndex++) {
            List<MealTemplate> meals = new ArrayList<>();
            for (int mealIndex = 0; mealIndex < mealCount; mealIndex++)
                meals.add(mealCatalog.get((dayIndex + mealIndex) % mealCatalog.size()));
            List<ExerciseTemplate> exercises =
                    dayIndex % 7 < exerciseDays
                            ? defaultExerciseDay(movementType, exerciseSession++)
                            : List.of();
            String mealSummary = meals.isEmpty() ? "" : "단백질과 영양 균형을 맞춘 식단";
            String exerciseSummary =
                    exercises.stream()
                            .filter(exercise -> "MAIN_EXERCISE".equals(exercise.sectionType()))
                            .map(ExerciseTemplate::sectionTitle)
                            .findFirst()
                            .orElse("");
            days.add(
                    new RoutineDayPlan(
                            start.plusDays(dayIndex),
                            mealSummary,
                            exerciseSummary,
                            meals,
                            exercises));
        }
        return new RoutinePlan("맞춤 웰니스 루틴", "개인 제약을 반영한 맞춤 웰니스 루틴입니다.", days, List.of());
    }

    private List<ExerciseTemplate> defaultExerciseDay(String movementType, int session) {
        String[] warmups = {"제자리 걷기", "어깨 돌리기", "고양이 소 자세"};
        String[][] mainExercises = {
            {"복부 크런치", "버드독", "글루트 브리지"},
            {"의자 스쿼트", "벽 밀기", "사이드 레그레이즈"},
            {"데드버그", "무릎 플랭크", "힙 힌지"}
        };
        String[] cooldowns = {"어린이 자세", "햄스트링 스트레칭", "누운 허리 회전"};
        int variant = session % mainExercises.length;
        List<ExerciseTemplate> result = new ArrayList<>();
        result.add(
                new ExerciseTemplate(
                        movementType,
                        "WARM_UP",
                        "준비 운동",
                        warmups[variant],
                        30,
                        "SECONDS",
                        1,
                        0,
                        3));
        for (String title : mainExercises[variant])
            result.add(
                    new ExerciseTemplate(
                            movementType,
                            "MAIN_EXERCISE",
                            switch (variant) {
                                case 0 -> "코어 안정화";
                                case 1 -> "하체와 상체 기초 근력";
                                default -> "전신 균형과 가동성";
                            },
                            title,
                            12 + session / 3 * 2,
                            "REPETITIONS",
                            2,
                            30,
                            5));
        result.add(
                new ExerciseTemplate(
                        movementType,
                        "COOL_DOWN",
                        "마무리 스트레칭",
                        cooldowns[variant],
                        30,
                        "SECONDS",
                        1,
                        0,
                        3));
        return result;
    }

    private String mealSectionTitle(String mealType) {
        return switch (mealType) {
            case "BREAKFAST" -> "아침 식단";
            case "LUNCH" -> "점심 식단";
            case "DINNER" -> "저녁 식단";
            default -> "간식";
        };
    }

    private int mealHour(String mealType, int index) {
        return switch (mealType) {
            case "BREAKFAST" -> 7;
            case "LUNCH" -> 12;
            case "DINNER" -> 18;
            default -> Math.min(23, 15 + index);
        };
    }

    public record RoutinePlan(
            String title,
            String description,
            List<RoutineDayPlan> days,
            List<String> safetyNotes) {}

    public record RoutineDayPlan(
            LocalDate scheduledDate,
            String mealSummaryTitle,
            String exerciseSummaryTitle,
            List<MealTemplate> meals,
            List<ExerciseTemplate> exerciseItems) {}

    public record MealTemplate(
            String mealType,
            String title,
            int calories,
            int carbohydrateGrams,
            int proteinGrams,
            int fatGrams) {}

    public record ExerciseTemplate(
            String activityType,
            String sectionType,
            String sectionTitle,
            String title,
            double targetValue,
            String targetUnit,
            int sets,
            int restSeconds,
            int estimatedMinutes) {}

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
        JsonNode dailySummaries = parseDailySummaries(r.getDailySummaries());
        List<DayView> dayViews =
                byDate.entrySet().stream()
                        .map(
                                entry ->
                                        dayView(
                                                entry.getKey(),
                                                entry.getValue(),
                                                dailySummaries.path(entry.getKey().toString())))
                        .toList();
        return new Detail(r, dayViews);
    }

    private JsonNode parseDailySummaries(String value) {
        try {
            return value == null || value.isBlank()
                    ? json.createObjectNode()
                    : json.readTree(value);
        } catch (Exception ignored) {
            return json.createObjectNode();
        }
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
    public ExerciseItem patchRoutineItem(
            Long routineId, Long routineItemId, RoutineRequests.PatchRoutineItem q) {
        requireContentMutable(owned(routineId));
        ExerciseItem item =
                items.findByIdAndRoutineIdAndDeletedAtIsNull(routineItemId, routineId)
                        .orElseThrow(() -> ApiException.notFound("루틴 항목을 찾을 수 없습니다."));
        requirePendingItem(item);
        item.patchItem(
                q.title(),
                q.content(),
                q.targetValue(),
                q.targetUnit(),
                q.sets(),
                q.restSeconds(),
                q.memo(),
                q.excludeFromAiAdjustment());
        return item;
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
        limits.lockJobCreation();
        if (key != null) {
            var existing =
                    jobs.findByUserIdAndTypeAndIdempotencyKey(
                            old.getUserId(), AiJob.Type.ROUTINE_ADJUSTMENT, key);
            if (existing.isPresent()) return existing.get();
        }
        var active =
                jobs.findFirstByUserIdAndTypeAndStatusInOrderByCreatedAtDesc(
                        old.getUserId(),
                        AiJob.Type.ROUTINE_ADJUSTMENT,
                        List.of(
                                AiJob.Status.PENDING,
                                AiJob.Status.PROCESSING,
                                AiJob.Status.RETRYING));
        if (active.isPresent()) return active.get();
        limits.authorizeJob(old.getUserId(), AiJob.Type.ROUTINE_ADJUSTMENT);
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

    private DayView dayView(LocalDate date, List<ExerciseItem> dayItems, JsonNode summary) {
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
                nullableText(summary, "mealTitle"),
                nullableText(summary, "exerciseTitle"),
                sectionViews);
    }

    private String nullableText(JsonNode node, String field) {
        String value = node.path(field).asText("").trim();
        return value.isEmpty() ? null : value;
    }

    public record Detail(Routine routine, List<DayView> days) {}

    public record DayView(
            Long routineDayId,
            String dayOfWeek,
            int week,
            LocalDate scheduledDate,
            Integer estimatedMinutes,
            String mealSummaryTitle,
            String exerciseSummaryTitle,
            List<SectionView> sections) {}

    public record SectionView(
            Long sectionId,
            String sectionType,
            String title,
            int order,
            List<ExerciseItem> exercises) {}
}
