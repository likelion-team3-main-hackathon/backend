package tri_lion.health.service.health;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tri_lion.health.domain.health.Analysis;
import tri_lion.health.domain.health.HealthDocument;
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
    private final UserRepositories.Profiles profiles;
    private final ObjectStorage storage;
    private final ObjectMapper json;

    public HealthAiTaskService(
            HealthRepositories.Analyses analyses,
            HealthRepositories.Documents documents,
            HealthRepositories.Jobs jobs,
            UserRepositories.Profiles profiles,
            ObjectStorage storage,
            ObjectMapper json) {
        this.analyses = analyses;
        this.documents = documents;
        this.jobs = jobs;
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
        List<AiClients.DocumentInput> inputs = new ArrayList<>();
        for (Long id : ids) {
            HealthDocument document =
                    documents
                            .findByIdAndUserIdAndDeletedAtIsNull(id, job.userId())
                            .orElseThrow(() -> new IllegalArgumentException("분석 문서를 찾을 수 없습니다."));
            byte[] bytes = storage.get(document.getObjectKey());
            if (bytes == null || bytes.length == 0)
                throw new IllegalArgumentException("분석 문서 원본을 읽을 수 없습니다.");
            document.processing();
            inputs.add(
                    new AiClients.DocumentInput(
                            id,
                            document.getType().name(),
                            document.getMeasuredAt(),
                            document.getContentType(),
                            bytes));
        }
        return new PreparedHealthTask(ids, inputs, profileInput(job.userId()));
    }

    @Transactional
    public void complete(
            AiJobTransactions.JobSnapshot job,
            List<Long> documentIds,
            String summary,
            String result,
            String model) {
        Analysis analysis = analyses.findById(job.resultId()).orElseThrow();
        analysis.complete(summary, result, model, "health-v3-multi-document");
        documentIds.forEach(
                id ->
                        documents
                                .findByIdAndUserIdAndDeletedAtIsNull(id, job.userId())
                                .ifPresent(HealthDocument::processed));
        var aiJob = jobs.findForUpdateById(job.id()).orElseThrow();
        aiJob.complete(model, "health-v3-multi-document");
    }

    @Transactional
    public void fail(AiJobTransactions.JobSnapshot job, String reason) {
        analyses.findById(job.resultId()).ifPresent(analysis -> analysis.fail(reason));
        try {
            for (var node : json.readTree(job.requestJson()).path("documentIds")) {
                documents
                        .findByIdAndUserIdAndDeletedAtIsNull(node.asLong(), job.userId())
                        .ifPresent(HealthDocument::fail);
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
            List<AiClients.DocumentInput> documents,
            Map<String, Object> profile) {}
}
