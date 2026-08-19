package tri_lion.health.external.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

@Configuration
@ConditionalOnProperty(name = "app.ai.provider", havingValue = "gemini")
public class GeminiAiClients {
    @Bean
    GeminiGateway geminiGateway(
            ObjectMapper json,
            @Value("${app.ai.gemini.api-key}") String apiKey,
            @Value("${app.ai.gemini.base-url:https://generativelanguage.googleapis.com/v1beta}")
                    String baseUrl,
            @Value("${app.ai.connect-timeout-seconds:5}") int connectTimeout,
            @Value("${app.ai.read-timeout-seconds:60}") int readTimeout,
            @Value("${app.ai.debug-log-responses:false}") boolean debugLogResponses) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException("Gemini 사용 시 GOOGLE_API_KEY가 필요합니다.");
        }
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Duration.ofSeconds(connectTimeout));
        requestFactory.setReadTimeout(Duration.ofSeconds(readTimeout));
        RestClient client =
                RestClient.builder()
                        .baseUrl(baseUrl)
                        .defaultHeader("x-goog-api-key", apiKey)
                        .requestFactory(requestFactory)
                        .build();
        return new GeminiGateway(client, json, debugLogResponses);
    }

    @Bean
    AiClients.OcrClient geminiOcrClient(
            GeminiGateway gateway,
            PromptCatalog prompts,
            @Value("${app.ai.models.analysis:gemini-3.5-flash-lite}") String model) {
        return new AiClients.OcrClient() {
            @Override
            public String extract(List<AiClients.DocumentInput> documents) {
                return gateway.generate(
                        model,
                        prompts.documentExtraction().content(),
                        Schemas.DOCUMENT_EXTRACTION,
                        documents);
            }

            @Override
            public String modelVersion() {
                return model;
            }

            @Override
            public String promptVersion() {
                return prompts.documentExtraction().storedVersion();
            }
        };
    }

    @Bean
    AiClients.LlmClient geminiLlmClient(
            GeminiGateway gateway,
            PromptCatalog prompts,
            @Value("${app.ai.models.analysis:gemini-3.5-flash-lite}") String analysisModel,
            @Value("${app.ai.models.routine:gemini-3.5-flash-lite}") String routineModel,
            @Value("${app.ai.models.coaching:gemini-3.5-flash-lite}") String coachingModel) {
        return new AiClients.LlmClient() {
            @Override
            public String healthAnalysis(String input) {
                return healthAnalysis(input, List.of());
            }

            @Override
            public String healthAnalysis(String input, List<AiClients.DocumentInput> visualDocuments) {
                String visualInstruction = visualDocuments.isEmpty()
                        ? ""
                        : "\n추가로 제공된 전신 사진은 인바디·진료 문서와 동등한 참고 자료입니다. 인바디 수치나 사진 중 하나를 자동으로 우선하지 말고 두 자료의 일치점과 차이를 함께 비교하세요. 사진만으로 질병이나 체지방률을 확정하지 말고, visualBodyAssessment에 체형 분류, 추정 체지방률(범위 또는 null), 신뢰도와 한계를 작성하세요.";
                return gateway.generate(
                        analysisModel,
                        prompts.healthAnalysis().content() + visualInstruction + "\n입력 JSON:\n" + input,
                        Schemas.HEALTH_ANALYSIS,
                        visualDocuments);
            }

            @Override
            public String routineGeneration(String input) {
                return gateway.generate(
                        routineModel,
                        prompts.routineGeneration().content() + "\n입력 JSON:\n" + input,
                        Schemas.ROUTINE_PLAN,
                        List.of());
            }

            @Override
            public AiClients.CoachingResult coaching(String input) {
                String result =
                        gateway.generate(
                                coachingModel,
                                prompts.recordCoaching().content() + "\n입력 JSON:\n" + input,
                                Schemas.COACHING,
                                List.of());
                try {
                    AiClients.CoachingResult coaching =
                            gateway.json().readValue(result, AiClients.CoachingResult.class);
                    if (coaching.message() == null
                            || coaching.message().isBlank()
                            || coaching.message().length() > 1000
                            || !Set.of("NORMAL", "CAUTION", "SEEK_PROFESSIONAL")
                                    .contains(coaching.safetyLevel()))
                        throw new GeminiResponseException("Gemini 코칭 응답 값이 올바르지 않습니다.");
                    return coaching;
                } catch (Exception exception) {
                    if (exception instanceof GeminiResponseException responseException)
                        throw responseException;
                    throw new GeminiResponseException("Gemini 코칭 응답을 해석할 수 없습니다.", exception);
                }
            }

            @Override
            public boolean live() {
                return true;
            }

            @Override
            public String analysisModelVersion() {
                return analysisModel;
            }

            @Override
            public String routineModelVersion() {
                return routineModel;
            }

            @Override
            public String coachingModelVersion() {
                return coachingModel;
            }

            @Override
            public String analysisPromptVersion() {
                return prompts.healthAnalysis().storedVersion();
            }

            @Override
            public String routinePromptVersion() {
                return prompts.routineGeneration().storedVersion();
            }

            @Override
            public String coachingPromptVersion() {
                return prompts.recordCoaching().storedVersion();
            }
        };
    }

    static final class GeminiGateway {
        private static final Logger log = LoggerFactory.getLogger(GeminiGateway.class);
        private final RestClient client;
        private final ObjectMapper json;
        private final boolean debugLogResponses;

        GeminiGateway(RestClient client, ObjectMapper json, boolean debugLogResponses) {
            this.client = client;
            this.json = json;
            this.debugLogResponses = debugLogResponses;
        }

        ObjectMapper json() {
            return json;
        }

        String generate(
                String model,
                String prompt,
                Map<String, Object> responseSchema,
                List<AiClients.DocumentInput> documents) {
            List<Map<String, Object>> parts = new ArrayList<>();
            for (AiClients.DocumentInput document : documents) {
                parts.add(
                        Map.of(
                                "text",
                                "sourceDocumentId="
                                        + document.documentId()
                                        + ", declaredDocumentType="
                                        + Optional.ofNullable(document.documentType())
                                                .orElse("OTHER")
                                        + ", measuredAt="
                                        + Optional.ofNullable(document.measuredAt())
                                                .map(Object::toString)
                                                .orElse("unknown")));
                parts.add(
                        Map.of(
                                "inlineData",
                                Map.of(
                                        "mimeType",
                                        document.contentType(),
                                        "data",
                                        Base64.getEncoder().encodeToString(document.bytes()))));
            }
            parts.add(Map.of("text", prompt));
            Map<String, Object> body =
                    Map.of(
                            "contents", List.of(Map.of("role", "user", "parts", parts)),
                            "generationConfig",
                                    Map.of(
                                            "responseMimeType",
                                            "application/json",
                                            "responseJsonSchema",
                                            responseSchema));
            try {
                JsonNode response =
                        client.post()
                                .uri("/models/{model}:generateContent", model)
                                .body(body)
                                .retrieve()
                                .body(JsonNode.class);
                if (response == null) throw new GeminiResponseException("Gemini 응답이 비어 있습니다.");
                JsonNode candidates = response.path("candidates");
                if (!candidates.isArray() || candidates.isEmpty()) {
                    throw new GeminiResponseException("Gemini가 결과 후보를 반환하지 않았습니다.");
                }
                String text =
                        candidates
                                .get(0)
                                .path("content")
                                .path("parts")
                                .get(0)
                                .path("text")
                                .asText();
                if (text.isBlank()) throw new GeminiResponseException("Gemini 결과 본문이 비어 있습니다.");
                json.readTree(text);
                if (debugLogResponses) {
                    log.info(
                            "AI_DEBUG_RESPONSE model={} characters={} response={}",
                            model,
                            text.length(),
                            text);
                }
                return text;
            } catch (GeminiResponseException exception) {
                throw exception;
            } catch (RestClientResponseException exception) {
                throw new GeminiResponseException(
                        "Gemini API 응답 오류 (HTTP " + exception.getStatusCode().value() + ")",
                        exception);
            } catch (ResourceAccessException exception) {
                throw new GeminiResponseException("Gemini API 연결 또는 응답 시간 초과", exception);
            } catch (Exception exception) {
                throw new GeminiResponseException("Gemini API 호출에 실패했습니다.", exception);
            }
        }
    }

    public static class GeminiResponseException extends RuntimeException {
        public GeminiResponseException(String message) {
            super(message);
        }

        public GeminiResponseException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    private static final class Schemas {
        private static final Map<String, Object> DOCUMENT_EXTRACTION =
                schema(
                        """
                        {"type":"object","properties":{"documents":{"type":"array","items":{"type":"object","properties":{"documentId":{"type":"integer"},"documentType":{"type":"string"},"measuredDate":{"type":["string","null"]},"measurements":{"type":"array","items":{"type":"object","properties":{"code":{"type":"string"},"label":{"type":"string"},"bodyPart":{"type":["string","null"]},"bodySide":{"type":["string","null"]},"value":{"type":["number","null"]},"textValue":{"type":["string","null"]},"unit":{"type":["string","null"]},"referenceMin":{"type":["number","null"]},"referenceMax":{"type":["number","null"]},"sourceText":{"type":"string"},"confidence":{"type":"number"}},"required":["code","label","bodyPart","bodySide","value","textValue","unit","referenceMin","referenceMax","sourceText","confidence"]}},"keyFacts":{"type":"array","items":{"type":"object","properties":{"category":{"type":"string","enum":["DIAGNOSIS","ALLERGY","OBSERVATION","RECOMMENDATION"]},"label":{"type":"string"},"value":{"type":"string"},"sourceText":{"type":"string"},"confidence":{"type":"number"}},"required":["category","label","value","sourceText","confidence"]}},"unreadableFields":{"type":"array","items":{"type":"string"}}},"required":["documentId","documentType","measuredDate","measurements","keyFacts","unreadableFields"]}}},"required":["documents"]}
                        """);
        private static final Map<String, Object> HEALTH_ANALYSIS =
                schema(
                        """
                        {
                          "type":"object",
                          "properties":{
                            "summary":{"type":"string"},
                            "documentFindings":{"type":"array","items":{"type":"object","properties":{"sourceDocumentId":{"type":"integer"},"documentType":{"type":"string"},"summary":{"type":"string"},"keyFindings":{"type":"array","items":{"type":"string"}}},"required":["sourceDocumentId","documentType","summary","keyFindings"]}},
                            "bodyCompositionFindings":{"type":"array","items":{"type":"object","properties":{"sourceDocumentId":{"type":"integer"},"label":{"type":"string"},"value":{"type":"number"},"unit":{"type":"string"},"interpretation":{"type":"string"}},"required":["sourceDocumentId","label","value","unit","interpretation"]}},
                            "allergyFindings":{"type":"array","items":{"type":"object","properties":{"sourceDocumentId":{"type":"integer"},"allergen":{"type":"string"},"result":{"type":"string"},"severity":{"type":"string"}},"required":["sourceDocumentId","allergen","result","severity"]}},
                            "medicalFindings":{"type":"array","items":{"type":"object","properties":{"sourceDocumentId":{"type":"integer"},"title":{"type":"string"},"description":{"type":"string"}},"required":["sourceDocumentId","title","description"]}},
                            "visualBodyAssessment":{"type":"object","properties":{"bodyType":{"type":"string"},"estimatedBodyFatPercent":{"type":["number","null"]},"estimatedBodyFatRange":{"type":["string","null"]},"confidence":{"type":"number"},"summary":{"type":"string"},"limitations":{"type":"array","items":{"type":"string"}}},"required":["bodyType","estimatedBodyFatPercent","estimatedBodyFatRange","confidence","summary","limitations"]},
                            "goals":{"type":"array","items":{"type":"object","properties":{"type":{"type":"string","enum":["WEIGHT_LOSS","REHABILITATION","POSTURE_CORRECTION","MUSCLE_GAIN","GENERAL_WELLNESS"]},"description":{"type":"string"}},"required":["type","description"]}},
                            "precautions":{"type":"array","items":{"type":"string"}},
                            "nutritionConstraints":{"type":"array","items":{"type":"string"}},
                            "exerciseConstraints":{"type":"array","items":{"type":"string"}},
                            "routineRecommendations":{"type":"array","items":{"type":"object","properties":{"id":{"type":"string","enum":["MEAL_PRIMARY","MEAL_ALTERNATIVE","EXERCISE_PRIMARY","EXERCISE_ALTERNATIVE"]},"category":{"type":"string","enum":["MEAL","EXERCISE"]},"title":{"type":"string"},"description":{"type":"string"},"durationWeeks":{"type":"integer"},"mealCountPerDay":{"type":"integer"},"exerciseDaysPerWeek":{"type":"integer"},"preferredExerciseTypes":{"type":"array","items":{"type":"string"}},"tags":{"type":"array","items":{"type":"string"}},"rationale":{"type":"string"}},"required":["id","category","title","description","durationWeeks","mealCountPerDay","exerciseDaysPerWeek","preferredExerciseTypes","tags","rationale"]}},
                            "disclaimer":{"type":"string"}
                          },
                          "required":["summary","documentFindings","bodyCompositionFindings","allergyFindings","medicalFindings","goals","precautions","nutritionConstraints","exerciseConstraints","routineRecommendations","disclaimer"]
                        }
                        """);
        private static final Map<String, Object> ROUTINE_PLAN =
                schema(
                        """
                        {
                          "type": "object",
                          "properties": {
                            "title": {"type": "string"},
                            "description": {"type": "string"},
                            "days": {
                              "type": "array",
                              "items": {
                                "type": "object",
                                "properties": {
                                  "scheduledDate": {"type": "string"},
                                  "mealSummaryTitle": {"type": "string"},
                                  "exerciseSummaryTitle": {"type": "string"},
                                  "meals": {
                                    "type": "array",
                                    "items": {
                                      "type": "object",
                                      "properties": {
                                        "mealType": {"type": "string", "enum": ["BREAKFAST", "LUNCH", "DINNER", "SNACK"]},
                                        "title": {"type": "string"},
                                        "calories": {"type": "integer"},
                                        "carbohydrateGrams": {"type": "integer"},
                                        "proteinGrams": {"type": "integer"},
                                        "fatGrams": {"type": "integer"}
                                      },
                                      "required": ["mealType", "title", "calories", "carbohydrateGrams", "proteinGrams", "fatGrams"]
                                    }
                                  },
                                  "exerciseItems": {
                                    "type": "array",
                                    "items": {
                                      "type": "object",
                                      "properties": {
                                        "activityType": {"type": "string", "enum": ["EXERCISE", "REHABILITATION"]},
                                        "sectionType": {"type": "string", "enum": ["WARM_UP", "MAIN_EXERCISE", "COOL_DOWN"]},
                                        "sectionTitle": {"type": "string"},
                                        "title": {"type": "string"},
                                        "targetValue": {"type": "number"},
                                        "targetUnit": {"type": "string", "enum": ["SECONDS", "MINUTES", "REPETITIONS", "METERS", "KILOMETERS"]},
                                        "sets": {"type": "integer"},
                                        "restSeconds": {"type": "integer"},
                                        "estimatedMinutes": {"type": "integer"}
                                      },
                                      "required": ["activityType", "sectionType", "sectionTitle", "title", "targetValue", "targetUnit", "sets", "restSeconds", "estimatedMinutes"]
                                    }
                                  }
                                },
                                "required": ["scheduledDate", "mealSummaryTitle", "exerciseSummaryTitle", "meals", "exerciseItems"]
                              }
                            },
                            "safetyNotes": {"type": "array", "items": {"type": "string"}}
                          },
                          "required": ["title", "description", "days", "safetyNotes"]
                        }
                        """);
        private static final Map<String, Object> COACHING =
                schema(
                        """
                        {"type":"object","properties":{"message":{"type":"string"},"safetyLevel":{"type":"string","enum":["NORMAL","CAUTION","SEEK_PROFESSIONAL"]}},"required":["message","safetyLevel"]}
                        """);

        @SuppressWarnings("unchecked")
        private static Map<String, Object> schema(String value) {
            try {
                return new ObjectMapper().readValue(value, Map.class);
            } catch (Exception exception) {
                throw new ExceptionInInitializerError(exception);
            }
        }
    }

    static void validateSchemas() {
        if (Schemas.DOCUMENT_EXTRACTION.isEmpty()
                || Schemas.HEALTH_ANALYSIS.isEmpty()
                || Schemas.ROUTINE_PLAN.isEmpty()
                || Schemas.COACHING.isEmpty())
            throw new IllegalStateException("Gemini Schema가 비어 있습니다.");
    }
}
