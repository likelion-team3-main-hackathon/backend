package tri_lion.health.service.record;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import tri_lion.health.domain.record.PoseAnalysis;
import tri_lion.health.exception.ApiException;
import tri_lion.health.external.ai.PoseAnalysisAiClient;
import tri_lion.health.external.storage.ObjectStorage;
import tri_lion.health.repository.record.PoseAnalysisRepository;
import tri_lion.health.repository.routine.RoutineRepositories;
import tri_lion.health.security.AuthenticatedUser;

@Service
public class PoseAnalysisService {
    private final PoseAnalysisRepository analyses;
    private final RoutineRepositories.Items items;
    private final RoutineRepositories.Routines routines;
    private final RecordService records;
    private final PoseAnalysisAiClient ai;
    private final ObjectStorage storage;
    private final AuthenticatedUser auth;
    private final ObjectMapper json;

    public PoseAnalysisService(
            PoseAnalysisRepository analyses,
            RoutineRepositories.Items items,
            RoutineRepositories.Routines routines,
            RecordService records,
            PoseAnalysisAiClient ai,
            ObjectStorage storage,
            AuthenticatedUser auth,
            ObjectMapper json) {
        this.analyses = analyses;
        this.items = items;
        this.routines = routines;
        this.records = records;
        this.ai = ai;
        this.storage = storage;
        this.auth = auth;
        this.json = json;
    }

    @Transactional
    public PoseAnalysis create(
            MultipartFile image, Long routineItemId, String requestedExerciseName) {
        Long userId = auth.active().getId();
        String exerciseName = Optional.ofNullable(requestedExerciseName).orElse("").trim();
        if (routineItemId != null) {
            var item =
                    items.findById(routineItemId)
                            .filter(value -> value.getDeletedAt() == null)
                            .orElseThrow(() -> ApiException.notFound("운동 항목을 찾을 수 없습니다."));
            routines.findByIdAndUserIdAndDeletedAtIsNull(item.getRoutineId(), userId)
                    .orElseThrow(() -> ApiException.notFound("운동 항목을 찾을 수 없습니다."));
            if (!"EXERCISE".equals(item.getItemType())) {
                throw new IllegalArgumentException("운동 항목만 자세를 분석할 수 있습니다.");
            }
            if (exerciseName.isBlank()) exerciseName = item.getName();
        }
        if (exerciseName.isBlank() || exerciseName.length() > 200) {
            throw new IllegalArgumentException("분석할 운동명을 입력해 주세요.");
        }
        String imageKey = records.uploadImage(image);
        PoseAnalysisAiClient.Result result =
                ai.analyze(storage.get(imageKey), image.getContentType(), exerciseName);
        try {
            return analyses.save(
                    new PoseAnalysis(
                            userId,
                            routineItemId,
                            exerciseName,
                            imageKey,
                            result.poseScore(),
                            json.writeValueAsString(result.detectedIssues()),
                            json.writeValueAsString(result.feedback()),
                            BigDecimal.valueOf(result.confidence()),
                            result.safetyWarning(),
                            result.modelVersion(),
                            result.promptVersion()));
        } catch (Exception exception) {
            throw new IllegalArgumentException("자세 분석 결과를 저장하지 못했습니다.", exception);
        }
    }

    public PoseAnalysis get(Long id) {
        return analyses.findByIdAndUserId(id, auth.active().getId())
                .orElseThrow(() -> ApiException.notFound("자세 분석 결과를 찾을 수 없습니다."));
    }

    public Map<String, Object> view(PoseAnalysis analysis) {
        try {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("poseAnalysisId", analysis.getId());
            result.put("routineItemId", analysis.getRoutineItemId());
            result.put("exerciseName", analysis.getExerciseName());
            result.put("poseScore", analysis.getPoseScore());
            result.put("detectedIssues", json.readTree(analysis.getDetectedIssues()));
            result.put("feedback", json.readTree(analysis.getFeedback()));
            result.put("confidence", analysis.getConfidence());
            result.put(
                    "safetyWarning", Optional.ofNullable(analysis.getSafetyWarning()).orElse(""));
            result.put("modelVersion", analysis.getModelVersion());
            result.put("promptVersion", analysis.getPromptVersion());
            result.put("analyzedAt", analysis.getAnalyzedAt());
            return result;
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }
}
