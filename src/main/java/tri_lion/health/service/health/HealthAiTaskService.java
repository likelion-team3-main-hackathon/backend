package tri_lion.health.service.health;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tri_lion.health.domain.health.Analysis;
import tri_lion.health.domain.health.HealthDocument;
import tri_lion.health.domain.health.HealthMeasurement;
import tri_lion.health.domain.user.HealthProfile;
import tri_lion.health.external.ai.AiClients;
import tri_lion.health.external.storage.ObjectStorage;
import tri_lion.health.repository.health.HealthRepositories;
import tri_lion.health.repository.user.UserRepositories;

@Service
public class HealthAiTaskService {
    private final HealthRepositories.Analyses analyses;
    private final HealthRepositories.Documents documents;
    private final HealthRepositories.Jobs jobs;
    private final HealthRepositories.Measurements measurements;
    private final UserRepositories.Profiles profiles;
    private final ObjectStorage storage;
    private final ObjectMapper json;

    public HealthAiTaskService(
            HealthRepositories.Analyses analyses,
            HealthRepositories.Documents documents,
            HealthRepositories.Jobs jobs,
            HealthRepositories.Measurements measurements,
            UserRepositories.Profiles profiles,
            ObjectStorage storage,
            ObjectMapper json) {
        this.analyses = analyses;
        this.documents = documents;
        this.jobs = jobs;
        this.measurements = measurements;
        this.profiles = profiles;
        this.storage = storage;
        this.json = json;
    }

    @Transactional
    public PreparedHealthTask prepare(AiJobTransactions.JobSnapshot job) throws Exception {
        List<Long> ids = new ArrayList<>();
        for (var node : json.readTree(job.requestJson()).path("documentIds"))
            ids.add(node.asLong());
        if (ids.isEmpty()) throw new IllegalArgumentException("분석 문서가 없습니다.");
        Analysis analysis = analyses.findById(job.resultId()).orElseThrow();
        analysis.processing();
        List<Long> ocrDocumentIds = new ArrayList<>();
        List<AiClients.DocumentInput> inputs = new ArrayList<>();
        List<AiClients.DocumentInput> visualInputs = new ArrayList<>();
        for (Long id : ids) {
            HealthDocument document =
                    documents
                            .findByIdAndUserIdAndDeletedAtIsNull(id, job.userId())
                            .orElseThrow(() -> new IllegalArgumentException("분석 문서를 찾을 수 없습니다."));
            byte[] bytes = storage.get(document.getObjectKey());
            if (bytes == null || bytes.length == 0)
                throw new IllegalArgumentException("분석 문서 원본을 읽을 수 없습니다.");
            document.processing();
            AiClients.DocumentInput input =
                    new AiClients.DocumentInput(
                            id,
                            document.getType().name(),
                            document.getMeasuredAt(),
                            document.getContentType(),
                            bytes);
            if (document.getType() == HealthDocument.Type.BODY_PHOTO) visualInputs.add(input);
            else {
                ocrDocumentIds.add(id);
                inputs.add(input);
            }
        }
        return new PreparedHealthTask(ids, ocrDocumentIds, inputs, visualInputs, profileInput(job.userId()));
    }

    @Transactional
    public void complete(
            AiJobTransactions.JobSnapshot job,
            List<Long> documentIds,
            String summary,
            String result,
            String model,
            String promptVersion) {
        Analysis analysis = analyses.findById(job.resultId()).orElseThrow();
        analysis.complete(summary, result, model, promptVersion);
        documentIds.forEach(
                id ->
                        documents
                                .findByIdAndUserIdAndDeletedAtIsNull(id, job.userId())
                                .ifPresent(HealthDocument::processed));
        var aiJob = jobs.findForUpdateById(job.id()).orElseThrow();
        aiJob.complete(model, promptVersion);
    }

    @Transactional
    public void saveExtraction(
            Long userId, String extracted, String modelVersion, String promptVersion)
            throws Exception {
        for (var node : json.readTree(extracted).path("documents")) {
            long documentId = node.path("documentId").asLong();
            HealthDocument document =
                    documents.findByIdAndUserIdAndDeletedAtIsNull(documentId, userId).orElseThrow();
            document.extracted(node.toString(), modelVersion, promptVersion);
            measurements.deleteByDocumentId(documentId);
            LocalDate extractedMeasuredAt =
                    parseMeasuredDate(node.path("measuredDate").asText(null));
            if (extractedMeasuredAt != null) document.measuredAt(extractedMeasuredAt);
            LocalDate measuredAt = extractedMeasuredAt;
            if (measuredAt == null) measuredAt = document.getMeasuredAt();
            if (measuredAt == null)
                measuredAt = LocalDate.ofInstant(document.getCreatedAt(), ZoneId.of("Asia/Seoul"));
            for (var value : node.path("measurements")) {
                String metricCode =
                        canonicalCode(
                                value.path("code").asText(""), value.path("label").asText(""));
                measurements.save(
                        new HealthMeasurement(
                                userId,
                                documentId,
                                bodyCompositionMetric(metricCode) ? "BODY_COMPOSITION" : "CLINICAL",
                                metricCode,
                                limited(value.path("label").asText("측정값"), 200),
                                normalizeBodyPart(nullableText(value, "bodyPart", 40)),
                                normalizeBodySide(nullableText(value, "bodySide", 20)),
                                decimal(value, "value"),
                                nullableText(value, "textValue", 500),
                                nullableText(value, "unit", 40),
                                decimal(value, "referenceMin"),
                                decimal(value, "referenceMax"),
                                measuredAt,
                                decimal(value, "confidence"),
                                nullableText(value, "sourceText", 1000)));
            }
        }
    }

    public static LocalDate parseMeasuredDate(String value) {
        try {
            if (value == null || value.isBlank()) return null;
            String normalized = value.trim();
            if (normalized.length() >= 10
                    && normalized.substring(0, 10).matches("\\d{4}-\\d{2}-\\d{2}"))
                return LocalDate.parse(normalized.substring(0, 10));
            return LocalDate.parse(normalized);
        } catch (Exception ignored) {
            return null;
        }
    }

    private BigDecimal decimal(com.fasterxml.jackson.databind.JsonNode node, String field) {
        var value = node.path(field);
        return value.isNumber() ? value.decimalValue() : null;
    }

    private String nullableText(
            com.fasterxml.jackson.databind.JsonNode node, String field, int maxLength) {
        var value = node.path(field);
        if (value.isMissingNode() || value.isNull() || value.asText().isBlank()) return null;
        return limited(value.asText(), maxLength);
    }

    private String limited(String value, int maxLength) {
        return value.length() <= maxLength ? value : value.substring(0, maxLength);
    }

    private String normalizeBodyPart(String value) {
        if (value == null) return null;
        String normalized = value.trim().toUpperCase(Locale.ROOT);
        if (normalized.matches(".*(ARM|팔|상지).*")) return "ARM";
        if (normalized.matches(".*(LEG|다리|하지).*")) return "LEG";
        if (normalized.matches(".*(TRUNK|몸통|체간).*")) return "TRUNK";
        return limited(normalized, 40);
    }

    private String normalizeBodySide(String value) {
        if (value == null) return null;
        String normalized = value.trim().toUpperCase(Locale.ROOT);
        if (normalized.matches(".*(LEFT|좌|왼).*")) return "LEFT";
        if (normalized.matches(".*(RIGHT|우|오른).*")) return "RIGHT";
        return limited(normalized, 20);
    }

    private String canonicalCode(String code, String label) {
        String value = (code + " " + label).toUpperCase(Locale.ROOT);
        if (value.matches(".*(SKELETAL.*MUSCLE|골격근).*")) return "SKELETAL_MUSCLE_MASS_KG";
        if (value.matches(".*(BODY.*FAT.*PERCENT|체지방률).*")) return "BODY_FAT_PERCENT";
        if (value.matches(".*(BODY.*FAT.*MASS|체지방량).*")) return "BODY_FAT_MASS_KG";
        if (value.matches(".*(WEIGHT|체중).*")) return "WEIGHT_KG";
        if (value.matches(".*(SEGMENTAL.*LEAN|부위별.*근육).*")) return "SEGMENTAL_LEAN_MASS_KG";
        if (value.matches(".*(SEGMENTAL.*FAT|부위별.*지방).*")) return "SEGMENTAL_FAT_MASS_KG";
        if (value.matches(".*BMI.*")) return "BMI";
        return limited(code.isBlank() ? "OTHER" : code.toUpperCase(Locale.ROOT), 80);
    }

    private boolean bodyCompositionMetric(String code) {
        return Set.of(
                        "WEIGHT_KG",
                        "BODY_FAT_PERCENT",
                        "BODY_FAT_MASS_KG",
                        "SKELETAL_MUSCLE_MASS_KG",
                        "SEGMENTAL_LEAN_MASS_KG",
                        "SEGMENTAL_FAT_MASS_KG",
                        "BMI",
                        "SCORE")
                .contains(code);
    }

    @Transactional
    public void fail(AiJobTransactions.JobSnapshot job, String reason) {
        analyses.findById(job.resultId()).ifPresent(analysis -> analysis.fail(reason));
        try {
            for (var node : json.readTree(job.requestJson()).path("documentIds")) {
                documents
                        .findByIdAndUserIdAndDeletedAtIsNull(node.asLong(), job.userId())
                        .ifPresent(document -> document.extractionFailed(reason));
            }
        } catch (Exception ignored) {
        }
    }

    private Map<String, Object> profileInput(Long userId) {
        HealthProfile profile = profiles.findById(userId).orElse(null);
        if (profile == null) return Map.of();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("birthDate", profile.getBirthDate());
        result.put("gender", profile.getGender());
        result.put("heightCm", profile.getHeightCm());
        result.put("weightKg", profile.getWeightKg());
        result.put("targetWeightKg", profile.getTargetWeightKg());
        result.put("activityLevel", profile.getActivityLevel());
        result.put("availableExerciseMinutes", profile.getAvailableExerciseMinutes());
        result.put("exerciseDays", jsonValue(profile.getExerciseDays()));
        result.put("dietaryPreferences", jsonValue(profile.getDietaryPreferences()));
        result.put("allergies", jsonValue(profile.getAllergies()));
        result.put("dislikedFoods", jsonValue(profile.getDislikedFoods()));
        result.put("goals", jsonValue(profile.getGoals()));
        result.put("injuries", jsonValue(profile.getInjuries()));
        return result;
    }

    private Object jsonValue(String value) {
        if (value == null) return List.of();
        try {
            return json.readTree(value);
        } catch (Exception ignored) {
            return List.of();
        }
    }

    public record PreparedHealthTask(
            List<Long> documentIds,
            List<Long> ocrDocumentIds,
            List<AiClients.DocumentInput> documents,
            List<AiClients.DocumentInput> visualDocuments,
            Map<String, Object> profile) {}
}
