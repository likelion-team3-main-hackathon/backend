package tri_lion.health.external.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;

@Configuration
@ConditionalOnProperty(name = "app.ai.provider", havingValue = "fake", matchIfMissing = true)
public class FakeAiClients {
    private final ObjectMapper json = new ObjectMapper();

    @Bean
    AiClients.OcrClient ocr() {
        return new AiClients.OcrClient() {
            @Override
            public String extract(java.util.List<AiClients.DocumentInput> documents) {
                ObjectNode root = json.createObjectNode();
                var results = root.putArray("documents");
                documents.forEach(
                        document -> {
                            ObjectNode item = results.addObject();
                            item.put("documentId", document.documentId());
                            item.put(
                                    "documentType",
                                    document.documentType() == null
                                            ? "OTHER"
                                            : document.documentType());
                            item.putNull("measuredDate");
                            item.putArray("measurements");
                            item.putArray("keyFacts");
                            item.putArray("unreadableFields");
                        });
                return root.toString();
            }

            @Override
            public String modelVersion() {
                return "fake-v1";
            }

            @Override
            public String promptVersion() {
                return "fake-document-v1";
            }
        };
    }

    @Bean
    AiClients.LlmClient llm() {
        return new AiClients.LlmClient() {
            public String healthAnalysis(String i) {
                try {
                    ObjectNode result = json.createObjectNode();
                    result.put("summary", "모든 건강 문서와 온보딩 정보를 종합했습니다.");
                    var findings = result.putArray("documentFindings");
                    for (var document : json.readTree(i).path("documents")) {
                        ObjectNode finding = findings.addObject();
                        finding.put("sourceDocumentId", document.path("documentId").asLong());
                        finding.put("documentType", document.path("documentType").asText("OTHER"));
                        finding.put("summary", "문서에서 확인 가능한 건강 정보를 반영했습니다.");
                        finding.putArray("keyFindings");
                    }
                    result.putArray("bodyCompositionFindings");
                    result.putArray("allergyFindings");
                    result.putArray("medicalFindings");
                    result.putArray("goals")
                            .addObject()
                            .put("type", "GENERAL_WELLNESS")
                            .put("description", "꾸준한 활동 습관 형성");
                    result.putArray("precautions");
                    result.putArray("nutritionConstraints");
                    result.putArray("exerciseConstraints");
                    var recommendations = result.putArray("routineRecommendations");
                    recommendation(
                            recommendations.addObject(),
                            "MEAL_PRIMARY",
                            "MEAL",
                            "균형 식단 3주",
                            3,
                            3,
                            0);
                    recommendation(
                            recommendations.addObject(),
                            "MEAL_ALTERNATIVE",
                            "MEAL",
                            "가벼운 식단 2주",
                            2,
                            3,
                            0);
                    recommendation(
                            recommendations.addObject(),
                            "EXERCISE_PRIMARY",
                            "EXERCISE",
                            "기초 운동 3주",
                            3,
                            0,
                            3);
                    recommendation(
                            recommendations.addObject(),
                            "EXERCISE_ALTERNATIVE",
                            "EXERCISE",
                            "가벼운 활동 2주",
                            2,
                            0,
                            4);
                    result.put("disclaimer", "본 분석은 의료 진단을 대체하지 않습니다.");
                    return result.toString();
                } catch (Exception exception) {
                    throw new IllegalArgumentException(exception);
                }
            }

            private void recommendation(
                    ObjectNode node,
                    String id,
                    String category,
                    String title,
                    int weeks,
                    int meals,
                    int exerciseDays) {
                node.put("id", id);
                node.put("category", category);
                node.put("title", title);
                node.put("description", "온보딩 정보를 반영한 추천안입니다.");
                node.put("durationWeeks", weeks);
                node.put("mealCountPerDay", meals);
                node.put("exerciseDaysPerWeek", exerciseDays);
                var types = node.putArray("preferredExerciseTypes");
                if (exerciseDays > 0) types.add("WALKING");
                node.putArray("tags").add(category.equals("MEAL") ? "식단" : "운동");
                node.put("rationale", "건강 분석과 생활 습관에 맞춰 추천했습니다.");
            }

            public String routineGeneration(String i) {
                throw new UnsupportedOperationException("Fake 루틴은 결정적 템플릿으로 생성합니다.");
            }

            public AiClients.CoachingResult coaching(String i) {
                return new AiClients.CoachingResult("기록을 잘 남겼어요. 무리하지 말고 현재 강도를 유지하세요.", "NORMAL");
            }

            @Override
            public String analysisModelVersion() {
                return "fake-v1";
            }

            @Override
            public String routineModelVersion() {
                return "fake-v1";
            }

            @Override
            public String coachingModelVersion() {
                return "fake-v1";
            }

            @Override
            public String analysisPromptVersion() {
                return "fake-health-v1";
            }

            @Override
            public String routinePromptVersion() {
                return "fake-routine-v1";
            }

            @Override
            public String coachingPromptVersion() {
                return "fake-coaching-v1";
            }
        };
    }
}
