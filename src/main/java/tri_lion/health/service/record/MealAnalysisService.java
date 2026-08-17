package tri_lion.health.service.record;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import tri_lion.health.domain.record.*;
import tri_lion.health.dto.request.record.*;
import tri_lion.health.exception.ApiException;
import tri_lion.health.external.ai.MealPhotoAiClient;
import tri_lion.health.external.storage.ObjectStorage;
import tri_lion.health.repository.record.*;
import tri_lion.health.repository.routine.RoutineRepositories;
import tri_lion.health.security.AuthenticatedUser;

@Service
public class MealAnalysisService {
    private final MealAnalysisRepository analyses;
    private final RecordRepositories.Records records;
    private final RoutineRepositories.Routines routines;
    private final RoutineRepositories.Items items;
    private final RecordService recordService;
    private final MealPhotoAiClient ai;
    private final ObjectStorage storage;
    private final AuthenticatedUser auth;
    private final ObjectMapper json;

    public MealAnalysisService(MealAnalysisRepository analyses, RecordRepositories.Records records,
            RoutineRepositories.Routines routines, RoutineRepositories.Items items,
            RecordService recordService, MealPhotoAiClient ai, ObjectStorage storage,
            AuthenticatedUser auth, ObjectMapper json) {
        this.analyses = analyses; this.records = records; this.routines = routines; this.items = items;
        this.recordService = recordService; this.ai = ai; this.storage = storage; this.auth = auth; this.json = json;
    }

    @Transactional
    public MealAnalysis create(MultipartFile image, Long routineItemId, OffsetDateTime recordedAt) {
        String imageKey = recordService.uploadImage(image);
        byte[] bytes = storage.get(imageKey);
        MealPhotoAiClient.Result result = ai.analyze(bytes, image.getContentType());
        try {
            return analyses.save(new MealAnalysis(auth.active().getId(), routineItemId, imageKey,
                    json.writeValueAsString(result.foods()), json.writeValueAsString(totals(result.foods())),
                    BigDecimal.valueOf(result.confidence()), result.modelVersion(),
                    Optional.ofNullable(recordedAt).orElseGet(OffsetDateTime::now).toInstant()));
        } catch (Exception exception) { throw new IllegalArgumentException("식단 분석 결과를 저장하지 못했습니다.", exception); }
    }

    public MealAnalysis get(Long id) { return owned(id); }

    @Transactional
    public MealAnalysis update(Long id, MealAnalysisUpdateRequest request) {
        MealAnalysis analysis = owned(id);
        try {
            List<MealPhotoAiClient.Food> foods = request.foods().stream().map(f -> new MealPhotoAiClient.Food(
                    f.name(), f.servingGrams(), f.calories(), f.carbohydrateGrams(), f.proteinGrams(), f.fatGrams())).toList();
            analysis.revise(json.writeValueAsString(foods), json.writeValueAsString(totals(foods)));
            return analysis;
        } catch (Exception exception) { throw new IllegalArgumentException("수정한 식단을 저장하지 못했습니다.", exception); }
    }

    @Transactional
    public MealAnalysis confirm(Long id) {
        MealAnalysis analysis = owned(id);
        try {
            Map<String, Object> totals = json.readValue(analysis.getTotals(), new TypeReference<>() {});
            List<Map<String, Object>> foods = json.readValue(analysis.getFoods(), new TypeReference<>() {});
            Map<String, Object> details = new LinkedHashMap<>(totals);
            details.put("foods", foods); details.put("menu", foods.stream().map(f -> f.get("name")).toList());
            details.put("completed", true); details.put("source", "MEAL_PHOTO_ANALYSIS");
            ActivityRecord record = replaceCompletedMealRecord(analysis, details).orElseGet(() ->
                    recordService.create(new RecordRequest(analysis.getRoutineItemId(), ActivityType.MEAL,
                            analysis.getRecordedAt().atOffset(ZoneOffset.UTC), details, analysis.getImageKey(), null)));
            analysis.confirm(record.getId());
            return analysis;
        } catch (RuntimeException exception) { throw exception; }
        catch (Exception exception) { throw new IllegalArgumentException("식단 기록을 확정하지 못했습니다.", exception); }
    }

    private Optional<ActivityRecord> replaceCompletedMealRecord(
            MealAnalysis analysis, Map<String, Object> details) {
        if (analysis.getRoutineItemId() == null) return Optional.empty();
        return records.findFirstByUserIdAndRoutineItemIdAndStatus(
                        auth.active().getId(), analysis.getRoutineItemId(), "COMPLETED")
                .map(record -> {
                    try {
                        record.reviseMeal(
                                analysis.getRecordedAt(),
                                json.writeValueAsString(details),
                                analysis.getImageKey());
                        return record;
                    } catch (RuntimeException exception) {
                        throw exception;
                    } catch (Exception exception) {
                        throw new IllegalArgumentException("식단 기록을 수정하지 못했습니다.", exception);
                    }
                });
    }

    public Map<String, Object> daily(LocalDate date) {
        Long userId = auth.active().getId();
        ZoneId zone = ZoneId.of("Asia/Seoul");
        Instant from = date.atStartOfDay(zone).toInstant();
        Instant to = date.plusDays(1).atStartOfDay(zone).toInstant();
        Totals consumed = new Totals();
        for (ActivityRecord record : records.findByUserIdAndTypeAndPerformedAtBetweenOrderByPerformedAtDescCreatedAtDesc(userId, ActivityType.MEAL, from, to)) {
            try { consumed.add(json.readTree(record.getDetails())); } catch (Exception ignored) {}
        }
        Totals target = plannedTarget(userId, date);
        Totals difference = consumed.minus(target);
        return Map.of("date", date, "target", target, "consumed", consumed, "difference", difference,
                "comment", comment(difference), "isEstimate", true);
    }

    private Totals plannedTarget(Long userId, LocalDate date) {
        Totals target = new Totals();
        routines.findFirstByUserIdAndStartDateLessThanEqualAndEndDateGreaterThanEqualAndDeletedAtIsNullOrderByCreatedAtDesc(userId, date, date)
                .ifPresent(r -> items.findByRoutineIdAndScheduledDateAndItemTypeAndDeletedAtIsNull(r.getId(), date, "MEAL").forEach(item -> {
                    try { target.add(json.readTree(item.getContent())); } catch (Exception ignored) {}
                }));
        return target.calories > 0 ? target : new Totals(2000, 250, 120, 60);
    }

    private MealAnalysis owned(Long id) { return analyses.findByIdAndUserId(id, auth.active().getId())
            .orElseThrow(() -> ApiException.notFound("식단 분석을 찾을 수 없습니다.")); }

    private Map<String, Double> totals(List<MealPhotoAiClient.Food> foods) {
        Totals total = new Totals();
        foods.forEach(f -> { total.calories += f.calories(); total.carbohydrateGrams += f.carbohydrateGrams(); total.proteinGrams += f.proteinGrams(); total.fatGrams += f.fatGrams(); });
        return total.asMap();
    }

    private String comment(Totals difference) {
        if (difference.proteinGrams < 0) return "오늘 단백질이 목표보다 " + Math.round(-difference.proteinGrams) + "g 부족했어요.";
        if (difference.calories > 0) return "오늘 섭취 열량이 목표보다 " + Math.round(difference.calories) + "kcal 많았어요.";
        return "오늘 식단은 계획한 영양 목표에 가깝게 섭취했어요.";
    }

    public Map<String, Object> view(MealAnalysis a) {
        try { return Map.of("analysisId", a.getId(), "status", a.getStatus(), "foods", json.readTree(a.getFoods()),
                "totals", json.readTree(a.getTotals()), "confidence", a.getConfidence(), "imageKey", a.getImageKey(),
                "modelVersion", a.getModelVersion(), "recordId", Optional.ofNullable(a.getConfirmedRecordId()).orElse(0L)); }
        catch (Exception e) { throw new IllegalStateException(e); }
    }

    public static class Totals {
        public double calories, carbohydrateGrams, proteinGrams, fatGrams;
        public Totals() {}
        public Totals(double c, double carb, double protein, double fat) { calories=c; carbohydrateGrams=carb; proteinGrams=protein; fatGrams=fat; }
        void add(JsonNode n) { calories += number(n,"calories"); carbohydrateGrams += number(n,"carbohydrateGrams","carbs"); proteinGrams += number(n,"proteinGrams","protein"); fatGrams += number(n,"fatGrams","fat"); }
        Totals minus(Totals other) { return new Totals(calories-other.calories, carbohydrateGrams-other.carbohydrateGrams, proteinGrams-other.proteinGrams, fatGrams-other.fatGrams); }
        Map<String,Double> asMap() { return Map.of("calories",calories,"carbohydrateGrams",carbohydrateGrams,"proteinGrams",proteinGrams,"fatGrams",fatGrams); }
        private double number(JsonNode n,String... keys) { for(String k:keys) if(n.has(k)) return n.path(k).asDouble(); return 0; }
    }
}
