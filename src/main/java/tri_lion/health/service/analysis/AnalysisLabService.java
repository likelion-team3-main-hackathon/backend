package tri_lion.health.service.analysis;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.*;
import java.time.temporal.TemporalAdjusters;
import java.util.*;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tri_lion.health.domain.health.Analysis;
import tri_lion.health.domain.health.HealthMeasurement;
import tri_lion.health.domain.record.ActivityRecord;
import tri_lion.health.domain.record.ActivityType;
import tri_lion.health.domain.routine.ExerciseItem;
import tri_lion.health.domain.user.HealthProfile;
import tri_lion.health.repository.health.HealthRepositories;
import tri_lion.health.repository.record.RecordRepositories;
import tri_lion.health.repository.routine.RoutineRepositories;
import tri_lion.health.repository.user.UserRepositories;
import tri_lion.health.security.AuthenticatedUser;

@Service
public class AnalysisLabService {
    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private final AuthenticatedUser auth;
    private final RecordRepositories.Records records;
    private final RoutineRepositories.Items items;
    private final HealthRepositories.Measurements measurements;
    private final HealthRepositories.Documents documents;
    private final HealthRepositories.Analyses analyses;
    private final UserRepositories.Profiles profiles;
    private final ObjectMapper json;

    public AnalysisLabService(
            AuthenticatedUser auth,
            RecordRepositories.Records records,
            RoutineRepositories.Items items,
            HealthRepositories.Measurements measurements,
            HealthRepositories.Documents documents,
            HealthRepositories.Analyses analyses,
            UserRepositories.Profiles profiles,
            ObjectMapper json) {
        this.auth = auth;
        this.records = records;
        this.items = items;
        this.measurements = measurements;
        this.documents = documents;
        this.analyses = analyses;
        this.profiles = profiles;
        this.json = json;
    }

    public Map<String, Object> overview(String period, LocalDate anchorDate) {
        DateRange range = range(period, anchorDate);
        Map<String, Object> nutrition = nutrition(range.from(), range.to());
        Map<String, Object> exercise = exercise(range.from(), range.to());
        Map<String, Object> body = bodyComposition(range.to().minusYears(1), range.to());
        Map<String, Object> recentNutrition =
                "WEEKLY".equals(range.type())
                        ? nutrition
                        : nutrition(range.to().minusDays(6), range.to());
        Map<String, Object> recentExercise =
                "WEEKLY".equals(range.type())
                        ? exercise
                        : exercise(range.to().minusDays(6), range.to());
        List<Map<String, Object>> metrics = new ArrayList<>();
        metrics.add(metric("MEAL", "식단", nutrition));
        metrics.add(metric("EXERCISE", "운동", exercise));
        metrics.add(metric("BODY_COMPOSITION", "체성분", body));
        List<Integer> scores =
                metrics.stream()
                        .map(value -> (Integer) value.get("score"))
                        .filter(Objects::nonNull)
                        .toList();
        Integer overall =
                scores.isEmpty()
                        ? null
                        : (int)
                                Math.round(
                                        scores.stream()
                                                .mapToInt(Integer::intValue)
                                                .average()
                                                .orElse(0));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("period", Map.of("type", range.type(), "from", range.from(), "to", range.to()));
        result.put("overallScore", overall);
        result.put(
                "status",
                scores.isEmpty()
                        ? "INSUFFICIENT_DATA"
                        : scores.size() < metrics.size() ? "PARTIAL" : "AVAILABLE");
        result.put("summary", overallSummary(nutrition, exercise, body));
        result.put("metrics", metrics);
        result.put(
                "labPreviews",
                List.of(
                        metric("MEAL", "식단", recentNutrition),
                        metric("EXERCISE", "운동", recentExercise),
                        metric("BODY_COMPOSITION", "체성분", body)));
        result.put("recommendations", recommendations(nutrition, exercise, body));
        return result;
    }

    public Map<String, Object> nutrition(LocalDate from, LocalDate to) {
        validateRange(from, to);
        Long userId = auth.active().getId();
        List<ActivityRecord> mealRecords =
                rangeRecords(userId, from, to).stream()
                        .filter(record -> record.getType() == ActivityType.MEAL)
                        .filter(record -> "COMPLETED".equals(record.getStatus()))
                        .toList();
        Map<LocalDate, Nutrients> daily = new TreeMap<>();
        for (ActivityRecord record : mealRecords) {
            JsonNode details = parse(record.getDetails());
            LocalDate date = record.getPerformedAt().atZone(SEOUL).toLocalDate();
            daily.merge(date, nutrients(details), Nutrients::plus);
        }
        int recordedDays = (int) daily.values().stream().filter(Nutrients::hasData).count();
        Nutrients total = daily.values().stream().reduce(Nutrients.zero(), Nutrients::plus);
        Nutrients average = recordedDays == 0 ? Nutrients.zero() : total.divide(recordedDays);
        Nutrients targets = nutritionTargets(userId, from, to);
        Integer score = recordedDays == 0 ? null : nutritionScore(average, targets);
        List<Map<String, Object>> trend = new ArrayList<>();
        for (LocalDate date = from; !date.isAfter(to); date = date.plusDays(1))
            trend.add(nutrientMap(date, daily.getOrDefault(date, Nutrients.zero())));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", recordedDays == 0 ? "INSUFFICIENT_DATA" : "AVAILABLE");
        result.put("reasonCode", recordedDays == 0 ? "MEAL_RECORDS_REQUIRED" : null);
        result.put("message", recordedDays == 0 ? "최근 식단 수행 기록이 없습니다." : null);
        result.put("from", from);
        result.put("to", to);
        result.put("recordedDays", recordedDays);
        result.put("score", score);
        result.put("targets", nutrientMap(null, targets));
        result.put("averages", nutrientMap(null, average));
        result.put("dailyTrend", trend);
        result.put("calorieRatio", calorieRatio(average));
        result.put("constraints", analysisArray("nutritionConstraints"));
        result.put("summary", nutritionSummary(average, targets, recordedDays));
        return result;
    }

    public Map<String, Object> exercise(LocalDate from, LocalDate to) {
        validateRange(from, to);
        Long userId = auth.active().getId();
        List<ExerciseItem> planned =
                items.findScheduledForUser(userId, from, to).stream()
                        .filter(item -> !"MEAL".equals(item.getItemType()))
                        .filter(item -> "MAIN_EXERCISE".equals(item.getSectionType()))
                        .toList();
        Map<Long, ExerciseItem> byId = new HashMap<>();
        planned.forEach(item -> byId.put(item.getId(), item));
        List<ActivityRecord> completed =
                rangeRecords(userId, from, to).stream()
                        .filter(
                                record ->
                                        record.getType() == ActivityType.EXERCISE
                                                || record.getType() == ActivityType.REHABILITATION)
                        .filter(record -> "COMPLETED".equals(record.getStatus()))
                        .toList();
        Map<String, int[]> groups = new TreeMap<>();
        for (ExerciseItem item : planned) {
            int sets = Optional.ofNullable(item.getSets()).orElse(1);
            for (String group : muscleGroups(item))
                groups.computeIfAbsent(group, key -> new int[2])[1] += sets;
        }
        int completedSets = 0;
        int completedMinutes = 0;
        Map<LocalDate, int[]> weekly = new TreeMap<>();
        for (ActivityRecord record : completed) {
            JsonNode details = parse(record.getDetails());
            ExerciseItem item = byId.get(record.getRoutineItemId());
            int sets =
                    details.has("completedSets")
                            ? details.path("completedSets").asInt()
                            : details.path("totalSets")
                                    .asInt(
                                            item == null
                                                    ? 1
                                                    : Optional.ofNullable(item.getSets())
                                                            .orElse(1));
            int minutes =
                    details.has("durationMinutes")
                            ? details.path("durationMinutes").asInt()
                            : details.path("minutes")
                                    .asInt(
                                            item == null
                                                    ? 0
                                                    : Optional.ofNullable(
                                                                    item.getEstimatedMinutes())
                                                            .orElse(0));
            completedSets += Math.max(0, sets);
            completedMinutes += Math.max(0, minutes);
            LocalDate date = record.getPerformedAt().atZone(SEOUL).toLocalDate();
            LocalDate week = date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
            int[] weekValue = weekly.computeIfAbsent(week, key -> new int[2]);
            weekValue[0] += sets;
            weekValue[1] += minutes;
            if (item != null)
                for (String group : muscleGroups(item))
                    groups.computeIfAbsent(group, key -> new int[2])[0] += sets;
        }
        int recommendedSets =
                planned.stream()
                        .mapToInt(item -> Optional.ofNullable(item.getSets()).orElse(1))
                        .sum();
        Integer score =
                recommendedSets == 0 ? null : Math.min(100, completedSets * 100 / recommendedSets);
        List<Map<String, Object>> volumes =
                groups.entrySet().stream()
                        .map(entry -> muscleVolume(entry.getKey(), entry.getValue()))
                        .toList();
        List<Map<String, Object>> weeklyVolume =
                weekly.entrySet().stream()
                        .map(
                                entry ->
                                        Map.<String, Object>of(
                                                "weekStart",
                                                entry.getKey(),
                                                "completedSets",
                                                entry.getValue()[0],
                                                "durationMinutes",
                                                entry.getValue()[1]))
                        .toList();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put(
                "status",
                planned.isEmpty() && completed.isEmpty() ? "INSUFFICIENT_DATA" : "AVAILABLE");
        result.put(
                "reasonCode",
                planned.isEmpty() && completed.isEmpty() ? "EXERCISE_RECORDS_REQUIRED" : null);
        result.put(
                "message",
                planned.isEmpty() && completed.isEmpty() ? "최근 운동 계획과 수행 기록이 없습니다." : null);
        result.put("from", from);
        result.put("to", to);
        result.put("score", score);
        result.put("completedSets", completedSets);
        result.put("recommendedSets", recommendedSets);
        result.put("durationMinutes", completedMinutes);
        result.put("muscleGroupVolumes", volumes);
        result.put("weeklyVolume", weeklyVolume);
        result.put("summary", exerciseSummary(volumes, planned.isEmpty() && completed.isEmpty()));
        return result;
    }

    @Transactional
    public Map<String, Object> bodyComposition(LocalDate from, LocalDate to) {
        validateRange(from, to);
        Long userId = auth.active().getId();
        List<HealthMeasurement> values =
                measurements.findByUserIdAndCategoryAndMeasuredAtBetweenOrderByMeasuredAtAsc(
                        userId, "BODY_COMPOSITION", from, to);
        if (values.isEmpty()
                && measurements
                        .findByUserIdAndCategoryOrderByMeasuredAtAsc(userId, "BODY_COMPOSITION")
                        .isEmpty()) {
            backfillLegacyBodyMeasurements(userId);
            values =
                    measurements.findByUserIdAndCategoryAndMeasuredAtBetweenOrderByMeasuredAtAsc(
                            userId, "BODY_COMPOSITION", from, to);
        }
        Map<String, List<HealthMeasurement>> byCode = new LinkedHashMap<>();
        values.stream()
                .filter(value -> value.getNumericValue() != null && value.getMeasuredAt() != null)
                .forEach(
                        value ->
                                byCode.computeIfAbsent(
                                                value.getMetricCode(), key -> new ArrayList<>())
                                        .add(value));
        if (byCode.isEmpty()) return insufficientBody();
        Map<String, Object> latest = new LinkedHashMap<>();
        latest.put(
                "measuredAt",
                values.stream()
                        .map(HealthMeasurement::getMeasuredAt)
                        .filter(Objects::nonNull)
                        .max(LocalDate::compareTo)
                        .orElse(null));
        latest.put("weightKg", latestValue(byCode, "WEIGHT_KG"));
        latest.put("bodyFatPercent", latestValue(byCode, "BODY_FAT_PERCENT"));
        latest.put("skeletalMuscleMassKg", latestValue(byCode, "SKELETAL_MUSCLE_MASS_KG"));
        Map<String, Object> trends = new LinkedHashMap<>();
        trends.put("weight", trend(byCode.get("WEIGHT_KG")));
        trends.put("bodyFatPercent", trend(byCode.get("BODY_FAT_PERCENT")));
        trends.put("skeletalMuscleMass", trend(byCode.get("SKELETAL_MUSCLE_MASS_KG")));
        Map<String, Object> result = new LinkedHashMap<>();
        long measurementCount =
                values.stream()
                        .map(HealthMeasurement::getMeasuredAt)
                        .filter(Objects::nonNull)
                        .distinct()
                        .count();
        result.put("status", measurementCount < 2 ? "PARTIAL" : "AVAILABLE");
        result.put("score", null);
        result.put("measurementCount", measurementCount);
        result.put("summary", bodySummary(latest, measurementCount));
        result.put("latest", latest);
        result.put("trends", trends);
        result.put("segmentalComparison", segmental(values));
        result.put(
                "sourceDocumentIds",
                values.stream().map(HealthMeasurement::getDocumentId).distinct().toList());
        return result;
    }

    private Map<String, Object> insufficientBody() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", "INSUFFICIENT_DATA");
        result.put("score", null);
        result.put("reasonCode", "BODY_COMPOSITION_DOCUMENT_REQUIRED");
        result.put("message", "체성분 분석에 필요한 정보가 없습니다.");
        result.put("requiredData", List.of("INBODY_DOCUMENT"));
        result.put(
                "action",
                Map.of(
                        "type",
                        "UPLOAD_HEALTH_DOCUMENT",
                        "label",
                        "인바디 파일 등록하기",
                        "acceptedDocumentTypes",
                        List.of("INBODY")));
        result.put("latest", null);
        result.put(
                "trends",
                Map.of(
                        "weight",
                        List.of(),
                        "bodyFatPercent",
                        List.of(),
                        "skeletalMuscleMass",
                        List.of()));
        result.put("segmentalComparison", List.of());
        return result;
    }

    private void backfillLegacyBodyMeasurements(Long userId) {
        List<Analysis> completed =
                analyses.findByUserIdAndStatusOrderByCreatedAtDesc(
                                userId, Analysis.Status.COMPLETED, Pageable.unpaged())
                        .getContent();
        Set<String> saved = new HashSet<>();
        for (Analysis analysis : completed) {
            for (JsonNode finding : parse(analysis.getDetails()).path("bodyCompositionFindings")) {
                long documentId = finding.path("sourceDocumentId").asLong(0);
                String code = legacyBodyMetricCode(finding.path("label").asText(""));
                if (documentId <= 0 || code == null || !finding.path("value").isNumber()) continue;
                String key = documentId + ":" + code;
                if (!saved.add(key)) continue;
                documents
                        .findByIdAndUserIdAndDeletedAtIsNull(documentId, userId)
                        .ifPresent(
                                document -> {
                                    LocalDate measuredAt = document.getMeasuredAt();
                                    if (measuredAt == null) {
                                        Instant fallback =
                                                Optional.ofNullable(analysis.getCompletedAt())
                                                        .orElse(analysis.getCreatedAt());
                                        measuredAt = fallback.atZone(SEOUL).toLocalDate();
                                    }
                                    measurements.save(
                                            new HealthMeasurement(
                                                    userId,
                                                    documentId,
                                                    "BODY_COMPOSITION",
                                                    code,
                                                    finding.path("label").asText(code),
                                                    null,
                                                    null,
                                                    finding.path("value").decimalValue(),
                                                    null,
                                                    finding.path("unit").asText(null),
                                                    null,
                                                    null,
                                                    measuredAt,
                                                    null,
                                                    finding.path("interpretation").asText(null)));
                                });
            }
        }
    }

    private String legacyBodyMetricCode(String label) {
        String value = label.toUpperCase(Locale.ROOT);
        if (value.matches(".*(체중|WEIGHT).*")) return "WEIGHT_KG";
        if (value.matches(".*(체지방률|BODY.*FAT.*PERCENT).*")) return "BODY_FAT_PERCENT";
        if (value.matches(".*(체지방량|BODY.*FAT.*MASS).*")) return "BODY_FAT_MASS_KG";
        if (value.matches(".*(골격근|SKELETAL.*MUSCLE).*")) return "SKELETAL_MUSCLE_MASS_KG";
        if (value.matches(".*BMI.*")) return "BMI";
        return null;
    }

    private List<ActivityRecord> rangeRecords(Long userId, LocalDate from, LocalDate to) {
        Instant start = from.atStartOfDay(SEOUL).toInstant();
        Instant end = to.plusDays(1).atStartOfDay(SEOUL).toInstant();
        return records.findByUserIdAndPerformedAtBetweenOrderByPerformedAtDesc(userId, start, end);
    }

    private Nutrients nutritionTargets(Long userId, LocalDate from, LocalDate to) {
        List<ExerciseItem> meals =
                items.findScheduledForUser(userId, from, to).stream()
                        .filter(item -> "MEAL".equals(item.getItemType()))
                        .toList();
        Map<LocalDate, Nutrients> daily = new HashMap<>();
        for (ExerciseItem item : meals)
            daily.merge(
                    item.getScheduledDate(), nutrients(parse(item.getContent())), Nutrients::plus);
        if (!daily.isEmpty())
            return daily.values().stream()
                    .reduce(Nutrients.zero(), Nutrients::plus)
                    .divide(daily.size())
                    .withDefaults();
        HealthProfile profile = profiles.findById(userId).orElse(null);
        double weight =
                profile == null || profile.getWeightKg() == null
                        ? 65
                        : profile.getWeightKg().doubleValue();
        double calories = weight * 30;
        return new Nutrients(
                calories, calories * .5 / 4, weight * 1.6, calories * .25 / 9, 2.3, 25);
    }

    private JsonNode parse(String value) {
        try {
            return value == null ? json.createObjectNode() : json.readTree(value);
        } catch (Exception ignored) {
            return json.createObjectNode();
        }
    }

    private Nutrients nutrients(JsonNode value) {
        return new Nutrients(
                number(value, "calories"),
                number(value, "carbohydrateGrams", "carbs"),
                number(value, "proteinGrams", "protein"),
                number(value, "fatGrams", "fat"),
                number(value, "sodiumGrams", "sodium"),
                number(value, "fiberGrams", "fiber"));
    }

    private double number(JsonNode node, String... fields) {
        for (String field : fields)
            if (node.path(field).isNumber()) return node.path(field).asDouble();
        return 0;
    }

    private Map<String, Object> nutrientMap(LocalDate date, Nutrients value) {
        Map<String, Object> result = new LinkedHashMap<>();
        if (date != null) result.put("date", date);
        result.put("calories", rounded(value.calories()));
        result.put("carbohydrateGrams", rounded(value.carbs()));
        result.put("proteinGrams", rounded(value.protein()));
        result.put("fatGrams", rounded(value.fat()));
        result.put("sodiumGrams", rounded(value.sodium()));
        result.put("fiberGrams", rounded(value.fiber()));
        return result;
    }

    private Map<String, Object> calorieRatio(Nutrients value) {
        double total = value.carbs() * 4 + value.protein() * 4 + value.fat() * 9;
        if (total <= 0)
            return Map.of("carbohydratePercent", 0, "proteinPercent", 0, "fatPercent", 0);
        int carbs = (int) Math.round(value.carbs() * 4 / total * 100);
        int protein = (int) Math.round(value.protein() * 4 / total * 100);
        return Map.of(
                "carbohydratePercent",
                carbs,
                "proteinPercent",
                protein,
                "fatPercent",
                Math.max(0, 100 - carbs - protein));
    }

    private int nutritionScore(Nutrients actual, Nutrients target) {
        double protein = Math.min(1, actual.protein() / Math.max(1, target.protein()));
        double carbs = Math.min(1, actual.carbs() / Math.max(1, target.carbs()));
        double fat = Math.min(1, target.fat() / Math.max(target.fat(), actual.fat()));
        return (int) Math.round((protein + carbs + fat) / 3 * 100);
    }

    private String nutritionSummary(Nutrients actual, Nutrients target, int days) {
        if (days == 0) return "식단 기록을 남기면 영양소 달성률을 분석할 수 있습니다.";
        if (actual.protein() < target.protein() * .8) return "최근 기록에서 단백질 섭취가 목표보다 부족합니다.";
        if (actual.fat() > target.fat() * 1.1) return "최근 기록에서 지방 섭취가 권장량보다 높습니다.";
        return "최근 식단의 주요 영양소가 권장 범위에 가깝습니다.";
    }

    private List<String> analysisArray(String field) {
        Optional<Analysis> latest =
                analyses.findFirstByUserIdAndStatusOrderByCompletedAtDesc(
                        auth.active().getId(), Analysis.Status.COMPLETED);
        if (latest.isEmpty()) return List.of();
        JsonNode values = parse(latest.get().getDetails()).path(field);
        if (!values.isArray()) return List.of();
        List<String> result = new ArrayList<>();
        values.forEach(
                value ->
                        result.add(
                                value.isTextual()
                                        ? value.asText()
                                        : value.path("description").asText(value.toString())));
        return result;
    }

    private List<String> muscleGroups(ExerciseItem item) {
        JsonNode stored = parse(item.getMuscleGroups());
        if (stored.isArray() && !stored.isEmpty()) {
            List<String> result = new ArrayList<>();
            stored.forEach(value -> result.add(value.asText()));
            return result;
        }
        String value = (item.getSectionTitle() + " " + item.getName()).toLowerCase();
        if (value.matches(".*(가슴|체스트|푸시업).*")) return List.of("CHEST");
        if (value.matches(".*(등|로우|풀업).*")) return List.of("BACK");
        if (value.matches(".*(어깨|숄더|레터럴).*")) return List.of("SHOULDERS");
        if (value.matches(".*(하체|스쿼트|런지|힙|레그).*")) return List.of("LEGS");
        if (value.matches(".*(코어|복부|크런치|플랭크).*")) return List.of("CORE");
        return List.of("FULL_BODY");
    }

    private Map<String, Object> muscleVolume(String group, int[] values) {
        return Map.of(
                "muscleGroup",
                group,
                "label",
                muscleLabel(group),
                "completedSets",
                values[0],
                "recommendedSets",
                values[1],
                "achievementRate",
                values[1] == 0 ? 0 : Math.min(100, values[0] * 100 / values[1]));
    }

    private String muscleLabel(String group) {
        return switch (group) {
            case "CHEST" -> "가슴";
            case "BACK" -> "등";
            case "SHOULDERS" -> "어깨";
            case "ARMS" -> "팔";
            case "LEGS" -> "하체";
            case "CORE" -> "코어";
            default -> "전신";
        };
    }

    private String exerciseSummary(List<Map<String, Object>> volumes, boolean empty) {
        if (empty) return "운동 기록을 남기면 부위별 세트 달성률을 분석할 수 있습니다.";
        return volumes.stream()
                .min(Comparator.comparingInt(value -> (Integer) value.get("achievementRate")))
                .map(value -> value.get("label") + " 운동량을 우선 보완해 보세요.")
                .orElse("최근 운동 계획을 안정적으로 수행하고 있습니다.");
    }

    private BigDecimal latestValue(Map<String, List<HealthMeasurement>> byCode, String code) {
        List<HealthMeasurement> values = byCode.getOrDefault(code, List.of());
        return values.isEmpty() ? null : values.getLast().getNumericValue();
    }

    private List<Map<String, Object>> trend(List<HealthMeasurement> values) {
        if (values == null) return List.of();
        return values.stream()
                .map(
                        value ->
                                Map.<String, Object>of(
                                        "date",
                                        value.getMeasuredAt(),
                                        "value",
                                        value.getNumericValue()))
                .toList();
    }

    private List<Map<String, Object>> segmental(List<HealthMeasurement> values) {
        Map<String, Map<String, BigDecimal>> parts = new TreeMap<>();
        values.stream()
                .filter(
                        value ->
                                value.getBodyPart() != null
                                        && value.getBodySide() != null
                                        && value.getNumericValue() != null)
                .forEach(
                        value ->
                                parts.computeIfAbsent(value.getBodyPart(), key -> new HashMap<>())
                                        .put(
                                                value.getMetricCode() + "_" + value.getBodySide(),
                                                value.getNumericValue()));
        return parts.entrySet().stream()
                .map(
                        entry -> {
                            Map<String, Object> result = new LinkedHashMap<>();
                            result.put("bodyPart", entry.getKey());
                            result.put(
                                    "leftMuscleKg",
                                    entry.getValue().get("SEGMENTAL_LEAN_MASS_KG_LEFT"));
                            result.put(
                                    "rightMuscleKg",
                                    entry.getValue().get("SEGMENTAL_LEAN_MASS_KG_RIGHT"));
                            result.put(
                                    "leftFatKg",
                                    entry.getValue().get("SEGMENTAL_FAT_MASS_KG_LEFT"));
                            result.put(
                                    "rightFatKg",
                                    entry.getValue().get("SEGMENTAL_FAT_MASS_KG_RIGHT"));
                            return result;
                        })
                .toList();
    }

    private Map<String, Object> metric(String type, String label, Map<String, Object> source) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("type", type);
        result.put("label", label);
        result.put("score", source.get("score"));
        result.put("status", source.get("status"));
        result.put("note", source.getOrDefault("summary", source.get("message")));
        result.put("reasonCode", source.get("reasonCode"));
        result.put(
                "badge",
                switch (type) {
                    case "MEAL" -> source.get("recordedDays") + "일 기록";
                    case "EXERCISE" -> source.get("completedSets") + "세트 완료";
                    case "BODY_COMPOSITION" ->
                            source.get("measurementCount") == null
                                    ? "자료 필요"
                                    : source.get("measurementCount") + "회 측정";
                    default -> null;
                });
        return result;
    }

    private String bodySummary(Map<String, Object> latest, long measurementCount) {
        List<String> values = new ArrayList<>();
        if (latest.get("weightKg") != null)
            values.add("체중 " + displayNumber(latest.get("weightKg")) + "kg");
        if (latest.get("bodyFatPercent") != null)
            values.add("체지방률 " + displayNumber(latest.get("bodyFatPercent")) + "%");
        if (latest.get("skeletalMuscleMassKg") != null)
            values.add("골격근량 " + displayNumber(latest.get("skeletalMuscleMassKg")) + "kg");
        if (values.isEmpty()) return "체성분 측정값 " + measurementCount + "회가 저장되어 있습니다.";
        return "최신 " + String.join(" · ", values);
    }

    private String displayNumber(Object value) {
        if (value instanceof BigDecimal decimal) {
            return decimal.stripTrailingZeros().toPlainString();
        }
        return String.valueOf(value);
    }

    private String overallSummary(
            Map<String, Object> nutrition, Map<String, Object> exercise, Map<String, Object> body) {
        if ("INSUFFICIENT_DATA".equals(nutrition.get("status"))
                && "INSUFFICIENT_DATA".equals(exercise.get("status")))
            return "식단과 운동 기록을 남기면 종합 웰니스 분석을 시작할 수 있습니다.";
        if ("INSUFFICIENT_DATA".equals(body.get("status")))
            return "생활 기록 분석은 가능하지만 체성분 자료를 추가하면 더 정확하게 볼 수 있습니다.";
        return "식단·운동 수행 기록과 체성분 측정값을 함께 반영한 분석입니다.";
    }

    private List<String> recommendations(
            Map<String, Object> nutrition, Map<String, Object> exercise, Map<String, Object> body) {
        List<String> result = new ArrayList<>();
        result.add(String.valueOf(nutrition.get("summary")));
        result.add(String.valueOf(exercise.get("summary")));
        if ("INSUFFICIENT_DATA".equals(body.get("status")))
            result.add("인바디 파일을 등록해 체성분 변화를 확인하세요.");
        return result;
    }

    private DateRange range(String period, LocalDate anchor) {
        String type = Optional.ofNullable(period).orElse("DAILY").toUpperCase(Locale.ROOT);
        LocalDate date = Optional.ofNullable(anchor).orElse(LocalDate.now(SEOUL));
        return switch (type) {
            case "DAILY" -> new DateRange(type, date, date);
            case "WEEKLY" -> new DateRange(type, date.minusDays(6), date);
            case "MONTHLY" -> new DateRange(type, date.withDayOfMonth(1), date);
            default ->
                    throw new IllegalArgumentException("분석 기간은 DAILY, WEEKLY, MONTHLY 중 하나여야 합니다.");
        };
    }

    private void validateRange(LocalDate from, LocalDate to) {
        if (from == null || to == null || to.isBefore(from) || to.isAfter(from.plusYears(1)))
            throw new IllegalArgumentException("분석 조회 기간이 올바르지 않습니다.");
    }

    private double rounded(double value) {
        return BigDecimal.valueOf(value).setScale(1, RoundingMode.HALF_UP).doubleValue();
    }

    private record DateRange(String type, LocalDate from, LocalDate to) {}

    private record Nutrients(
            double calories,
            double carbs,
            double protein,
            double fat,
            double sodium,
            double fiber) {
        static Nutrients zero() {
            return new Nutrients(0, 0, 0, 0, 0, 0);
        }

        Nutrients plus(Nutrients other) {
            return new Nutrients(
                    calories + other.calories,
                    carbs + other.carbs,
                    protein + other.protein,
                    fat + other.fat,
                    sodium + other.sodium,
                    fiber + other.fiber);
        }

        Nutrients divide(double value) {
            return new Nutrients(
                    calories / value,
                    carbs / value,
                    protein / value,
                    fat / value,
                    sodium / value,
                    fiber / value);
        }

        boolean hasData() {
            return calories > 0 || carbs > 0 || protein > 0 || fat > 0;
        }

        Nutrients withDefaults() {
            return new Nutrients(
                    calories > 0 ? calories : (carbs * 4 + protein * 4 + fat * 9),
                    carbs,
                    protein,
                    fat,
                    sodium > 0 ? sodium : 2.3,
                    fiber > 0 ? fiber : 25);
        }
    }
}
