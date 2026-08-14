package tri_lion.health.service.health;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import tri_lion.health.domain.health.AiJob;
import tri_lion.health.external.ai.AiClients;
import tri_lion.health.service.record.RecordService;
import tri_lion.health.service.routine.RoutineService;

@Component
public class AiJobWorker {
    private static final Logger log = LoggerFactory.getLogger(AiJobWorker.class);
    private final AiJobTransactions transactions;
    private final HealthAiTaskService healthTasks;
    private final AiClients.OcrClient ocr;
    private final AiClients.LlmClient llm;
    private final ObjectMapper json;
    private final RoutineService routines;
    private final RecordService records;

    public AiJobWorker(
            AiJobTransactions transactions,
            HealthAiTaskService healthTasks,
            AiClients.OcrClient ocr,
            AiClients.LlmClient llm,
            ObjectMapper json,
            RoutineService routines,
            RecordService records) {
        this.transactions = transactions;
        this.healthTasks = healthTasks;
        this.ocr = ocr;
        this.llm = llm;
        this.json = json;
        this.routines = routines;
        this.records = records;
    }

    @Scheduled(fixedDelayString = "${app.worker.delay-ms:500}")
    public void work() {
        for (Long id : transactions.claimableIds()) transactions.claim(id).ifPresent(this::process);
    }

    private void process(AiJobTransactions.JobSnapshot job) {
        try {
            switch (job.type()) {
                case HEALTH_ANALYSIS -> processHealth(job);
                case ROUTINE_GENERATION, ROUTINE_ADJUSTMENT -> processRoutine(job);
                case RECORD_COACHING -> processCoaching(job);
                case CONTENT_PERSONALIZATION ->
                        throw new UnsupportedOperationException("콘텐츠 개인화는 아직 지원하지 않습니다.");
            }
        } catch (Exception exception) {
            String reason = failureReason(exception);
            log.warn("AI job failed: id={}, type={}, reason={}", job.id(), job.type(), reason);
            AiJob.Status status = transactions.retry(job, reason);
            if (status == AiJob.Status.FAILED && job.type() == AiJob.Type.HEALTH_ANALYSIS)
                healthTasks.fail(job, "AI 건강 분석을 완료하지 못했습니다.");
        }
    }

    private String failureReason(Exception exception) {
        Throwable cause = exception;
        while (cause != null) {
            if (cause
                    instanceof tri_lion.health.external.ai.GeminiAiClients.GeminiResponseException)
                return Optional.ofNullable(cause.getMessage()).orElse("Gemini API 처리 실패");
            if (cause instanceof IllegalArgumentException)
                return Optional.ofNullable(cause.getMessage()).orElse("AI 응답 검증 실패");
            if (cause instanceof com.fasterxml.jackson.core.JsonProcessingException)
                return "AI 응답 JSON 해석 실패";
            cause = cause.getCause();
        }
        return "외부 AI 처리 실패";
    }

    private void processHealth(AiJobTransactions.JobSnapshot job) throws Exception {
        HealthAiTaskService.PreparedHealthTask task = healthTasks.prepare(job);
        String extracted = ocr.extract(task.documents());
        validateExtraction(extracted, task.documentIds());
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("documents", json.readTree(maskDirectIdentifiers(extracted)).path("documents"));
        input.put("profile", task.profile());
        String result = llm.healthAnalysis(json.writeValueAsString(input));
        String summary = validateHealthAnalysis(result, task.documentIds());
        healthTasks.complete(job, task.documentIds(), summary, result, llm.analysisModelVersion());
    }

    private void processRoutine(AiJobTransactions.JobSnapshot job) throws Exception {
        AiJob detached = job.detachedJob();
        RoutineService.RoutinePlan plan = routines.plan(detached);
        routines.generate(job, plan);
    }

    private void processCoaching(AiJobTransactions.JobSnapshot job) {
        AiClients.CoachingResult coaching = llm.coaching(job.requestJson());
        records.saveCoaching(
                job.id(),
                job.userId(),
                job.resultId(),
                coaching.message(),
                coaching.safetyLevel(),
                llm.coachingModelVersion());
    }

    private void validateExtraction(String extracted, List<Long> expectedIds) throws Exception {
        var root = json.readTree(extracted);
        var extractedDocuments = root.path("documents");
        if (!extractedDocuments.isArray() || extractedDocuments.size() != expectedIds.size())
            throw new IllegalArgumentException("문서 추출 결과 개수가 일치하지 않습니다.");
        Set<Long> actualIds = new HashSet<>();
        for (var document : extractedDocuments) {
            actualIds.add(document.path("documentId").asLong());
            for (var measurement : document.path("measurements")) {
                double confidence = measurement.path("confidence").asDouble(-1);
                if (confidence < 0 || confidence > 1)
                    throw new IllegalArgumentException("문서 추출 신뢰도 범위가 잘못되었습니다.");
            }
        }
        if (!actualIds.equals(new HashSet<>(expectedIds)))
            throw new IllegalArgumentException("문서 추출 근거 ID가 일치하지 않습니다.");
    }

    private String maskDirectIdentifiers(String value) {
        return value.replaceAll("\\b\\d{6}[- ]?[1-4]\\d{6}\\b", "[MASKED_ID]")
                .replaceAll("\\b01[016789][- ]?\\d{3,4}[- ]?\\d{4}\\b", "[MASKED_PHONE]");
    }

    private String validateHealthAnalysis(String result, List<Long> expectedDocumentIds)
            throws Exception {
        var root = json.readTree(result);
        String summary = root.path("summary").asText().trim();
        String disclaimer = root.path("disclaimer").asText().trim();
        if (summary.isBlank() || summary.length() > 1000 || disclaimer.isBlank())
            throw new IllegalArgumentException("건강 분석 필수 결과가 없습니다.");
        Set<String> goals =
                Set.of(
                        "WEIGHT_LOSS",
                        "REHABILITATION",
                        "POSTURE_CORRECTION",
                        "MUSCLE_GAIN",
                        "GENERAL_WELLNESS");
        if (!root.path("goals").isArray() || root.path("goals").isEmpty())
            throw new IllegalArgumentException("건강 목표가 없습니다.");
        for (var goal : root.path("goals"))
            if (!goals.contains(goal.path("type").asText())
                    || goal.path("description").asText().isBlank())
                throw new IllegalArgumentException("건강 목표 형식이 잘못되었습니다.");
        for (String field : List.of("precautions", "nutritionConstraints", "exerciseConstraints"))
            if (!root.path(field).isArray())
                throw new IllegalArgumentException("건강 분석 배열 형식이 잘못되었습니다.");
        var documentFindings = root.path("documentFindings");
        if (!documentFindings.isArray() || documentFindings.size() != expectedDocumentIds.size())
            throw new IllegalArgumentException("건강 분석에 일부 문서 결과가 누락되었습니다.");
        Set<Long> analyzedDocumentIds = new HashSet<>();
        for (var finding : documentFindings) {
            analyzedDocumentIds.add(finding.path("sourceDocumentId").asLong());
            if (finding.path("summary").asText().isBlank()
                    || !finding.path("keyFindings").isArray())
                throw new IllegalArgumentException("문서별 건강 분석 형식이 잘못되었습니다.");
        }
        if (!analyzedDocumentIds.equals(new HashSet<>(expectedDocumentIds)))
            throw new IllegalArgumentException("건강 분석 문서 근거가 요청과 일치하지 않습니다.");
        for (String field :
                List.of("bodyCompositionFindings", "allergyFindings", "medicalFindings")) {
            if (!root.path(field).isArray())
                throw new IllegalArgumentException("문서 유형별 건강 분석 결과가 없습니다.");
            for (var finding : root.path(field))
                if (!analyzedDocumentIds.contains(finding.path("sourceDocumentId").asLong()))
                    throw new IllegalArgumentException("건강 분석 근거 문서 ID가 올바르지 않습니다.");
        }
        validateRoutineRecommendations(root.path("routineRecommendations"));
        return summary;
    }

    private void validateRoutineRecommendations(com.fasterxml.jackson.databind.JsonNode values) {
        if (!values.isArray() || values.size() != 4)
            throw new IllegalArgumentException("식단·운동 추천 루틴 요약이 모두 생성되지 않았습니다.");
        Set<String> ids = new HashSet<>();
        int meals = 0;
        int exercises = 0;
        for (var value : values) {
            String id = value.path("id").asText();
            String category = value.path("category").asText();
            int weeks = value.path("durationWeeks").asInt();
            int mealCount = value.path("mealCountPerDay").asInt(-1);
            int exerciseDays = value.path("exerciseDaysPerWeek").asInt(-1);
            if (!ids.add(id)
                    || !(id.startsWith(category + "_"))
                    || value.path("title").asText().isBlank()
                    || value.path("description").asText().isBlank()
                    || value.path("rationale").asText().isBlank()
                    || !value.path("tags").isArray()
                    || !value.path("preferredExerciseTypes").isArray()
                    || weeks < 2
                    || weeks > 4) throw new IllegalArgumentException("추천 루틴 요약 형식이 잘못되었습니다.");
            if ("MEAL".equals(category) && mealCount >= 1 && mealCount <= 6 && exerciseDays == 0)
                meals++;
            else if ("EXERCISE".equals(category)
                    && mealCount == 0
                    && exerciseDays >= 1
                    && exerciseDays <= 7) exercises++;
            else throw new IllegalArgumentException("추천 루틴 유형과 빈도가 일치하지 않습니다.");
        }
        if (meals != 2 || exercises != 2)
            throw new IllegalArgumentException("식단·운동 추천 루틴이 각각 2개 필요합니다.");
    }
}
