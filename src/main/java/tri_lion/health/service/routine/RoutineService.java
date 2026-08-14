package tri_lion.health.service.routine;

import com.fasterxml.jackson.databind.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import org.springframework.data.domain.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tri_lion.health.domain.health.*;
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

    public RoutineService(
            RoutineRepositories.Routines r,
            RoutineRepositories.Items i,
            HealthRepositories.Analyses a,
            HealthRepositories.Jobs j,
            AuthenticatedUser u,
            ObjectMapper o) {
        routines = r;
        items = i;
        analyses = a;
        jobs = j;
        auth = u;
        json = o;
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
        owned(routineId);
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
        owned(routineId);
        ExerciseItem i =
                items.findByIdAndRoutineIdAndDeletedAtIsNull(exerciseId, routineId)
                        .orElseThrow(() -> ApiException.notFound("운동 항목을 찾을 수 없습니다."));
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
        owned(r);
        items.findByIdAndRoutineIdAndDeletedAtIsNull(i, r)
                .orElseThrow(() -> ApiException.notFound("운동 항목을 찾을 수 없습니다."))
                .delete();
    }

    @Transactional
    public List<Long> order(Long r, Long s, List<Long> ids) {
        owned(r);
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
