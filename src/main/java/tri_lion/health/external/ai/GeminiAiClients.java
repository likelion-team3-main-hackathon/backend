package tri_lion.health.external.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.*;
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
            @Value("${app.ai.read-timeout-seconds:60}") int readTimeout) {
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
        return new GeminiGateway(client, json);
    }

    @Bean
    AiClients.OcrClient geminiOcrClient(
            GeminiGateway gateway,
            @Value("${app.ai.models.analysis:gemini-3.6-flash}") String model) {
        return new AiClients.OcrClient() {
            @Override
            public String extract(List<AiClients.DocumentInput> documents) {
                return gateway.generate(
                        model, Prompts.DOCUMENT_EXTRACTION, Schemas.DOCUMENT_EXTRACTION, documents);
            }

            @Override
            public String modelVersion() {
                return model;
            }
        };
    }

    @Bean
    AiClients.LlmClient geminiLlmClient(
            GeminiGateway gateway,
            @Value("${app.ai.models.analysis:gemini-3.6-flash}") String analysisModel,
            @Value("${app.ai.models.routine:gemini-3.6-flash}") String routineModel,
            @Value("${app.ai.models.coaching:gemini-3.5-flash-lite}") String coachingModel) {
        return new AiClients.LlmClient() {
            @Override
            public String healthAnalysis(String input) {
                return gateway.generate(
                        analysisModel,
                        Prompts.HEALTH_ANALYSIS + "\n입력 JSON:\n" + input,
                        Schemas.HEALTH_ANALYSIS,
                        List.of());
            }

            @Override
            public String routineGeneration(String input) {
                return gateway.generate(
                        routineModel,
                        Prompts.ROUTINE_GENERATION + "\n입력 JSON:\n" + input,
                        Schemas.ROUTINE_PLAN,
                        List.of());
            }

            @Override
            public AiClients.CoachingResult coaching(String input) {
                String result =
                        gateway.generate(
                                coachingModel,
                                Prompts.RECORD_COACHING + "\n입력 JSON:\n" + input,
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
        };
    }

    static final class GeminiGateway {
        private final RestClient client;
        private final ObjectMapper json;

        GeminiGateway(RestClient client, ObjectMapper json) {
            this.client = client;
            this.json = json;
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

    private static final class Prompts {
        private static final String DOCUMENT_EXTRACTION =
                """
                업로드된 모든 한국어 건강 문서를 문서별로 빠짐없이 읽고 명시적으로 보이는 정보만 추출한다.
                수치를 추측하거나 누락된 값을 생성하지 않는다. 이름, 연락처, 주소, 주민등록번호,
                병원 등록번호 등 직접 식별정보는 결과에 포함하지 않는다. 표의 검사명, 수치, 단위,
                참고 범위를 같은 행 기준으로 연결한다. 인바디는 체중, 골격근량, 체지방량, 체지방률,
                BMI, 내장지방 등 보이는 항목을 measurements에 각각 기록한다. 알레르기 검사지는 알레르겐,
                검사 결과·등급·수치와 단위를 keyFacts에 기록한다. 진단서의 진단명과 명시된 주의사항도
                keyFacts에 기록한다. 판독 불가 필드는 unreadableFields에 기록한다. 각 입력 문서마다
                documents 항목을 정확히 하나 생성하고 제공된 sourceDocumentId와 declaredDocumentType을 보존한다.
                """;
        private static final String HEALTH_ANALYSIS =
                """
                입력의 구조화된 모든 건강 문서와 온보딩 프로필을 함께 비교해 종합 웰니스 정보를 작성한다.
                특정 문서만 대표로 요약하지 않는다. documentFindings에는 입력 문서마다 정확히 하나의 결과를
                만들고 sourceDocumentId를 보존한다. 인바디 수치는 bodyCompositionFindings에, 알레르기 검사
                결과는 allergyFindings에, 진단서 내용은 medicalFindings에 근거 문서 ID와 함께 기록한다.
                문서 간 공통점과 운동·영양에 함께 영향을 주는 내용을 summary와 제약 조건에 통합한다.
                routineRecommendations에는 실제 루틴 내용을 만들지 말고 사용자가 고를 요약 카드만 만든다.
                식단 2개(MEAL_PRIMARY, MEAL_ALTERNATIVE), 운동·재활 2개(EXERCISE_PRIMARY,
                EXERCISE_ALTERNATIVE)를 만들고 제목, 2~4주 기간, 빈도, 태그와 추천 이유를 제공한다.
                의료 진단, 질병 확정, 약물 처방을 하지 않는다. 문서에 없는 사실을 만들지 않는다.
                심한 통증, 흉통, 호흡 곤란 같은 위험 신호가 있으면 운동 강도 대신 의료 전문가 상담을
                권한다. 사용자가 이해하기 쉬운 한국어로 간결하게 작성한다.
                """;
        private static final String ROUTINE_GENERATION =
                """
                건강 분석, 온보딩 프로필과 사용자 요청을 근거로 요청 기간 전체의 날짜별 루틴을 한 번에 만든다.
                startDate부터 durationWeeks * 7일을 하루도 빠짐없이 days에 오름차순으로 넣는다.
                각 날짜의 meals 수는 mealCountPerDay와 정확히 같아야 하며 0이면 빈 배열로 만든다.
                각 주마다 exerciseItems가 비어 있지 않은 날짜 수는 exerciseDaysPerWeek와 정확히 같아야 한다.
                운동일에는 WARM_UP, MAIN_EXERCISE, COOL_DOWN을 모두 포함한다. 날짜별 목표와 운동 구성을
                회복 및 점진적 향상을 고려해 변화시키고, 모든 운동일에 동일한 프로그램을 복제하지 않는다.
                식단도 알레르기, 선호, 목표 열량과 영양 균형을 고려해 날짜별로 다양하게 구성한다.
                문서에 없는 질병을 추정하지 말고 건강 분석의 주의사항, 부상과 운동 제약을 우선한다.
                selectedRoutineRecommendations가 있으면 선택된 카드의 제목, 설명, 기간, 빈도와 추천 이유를
                실제 루틴 구성에 반드시 반영한다. 선택되지 않은 추천 카드의 목표를 섞지 않는다.
                scheduledDate는 YYYY-MM-DD 형식으로 반환한다. DB ID, 상태, 영상 URL은 생성하지 않는다.
                진단이나 치료를 표방하지 않는다.
                """;
        private static final String RECORD_COACHING =
                """
                수행 기록을 바탕으로 2~3문장의 짧은 한국어 웰니스 코칭을 작성한다.
                비난하거나 의료 진단·처방하지 않는다. 통증 수준이 높거나 위험 신호가 있으면 운동을
                중단하고 의료 전문가와 상담하도록 안내한다.
                """;
    }

    private static final class Schemas {
        private static final Map<String, Object> DOCUMENT_EXTRACTION =
                schema(
                        """
                        {"type":"object","properties":{"documents":{"type":"array","items":{"type":"object","properties":{"documentId":{"type":"integer"},"documentType":{"type":"string"},"measuredDate":{"type":["string","null"]},"measurements":{"type":"array","items":{"type":"object","properties":{"code":{"type":"string"},"label":{"type":"string"},"value":{"type":["number","null"]},"textValue":{"type":["string","null"]},"unit":{"type":["string","null"]},"referenceMin":{"type":["number","null"]},"referenceMax":{"type":["number","null"]},"sourceText":{"type":"string"},"confidence":{"type":"number"}},"required":["code","label","value","textValue","unit","referenceMin","referenceMax","sourceText","confidence"]}},"keyFacts":{"type":"array","items":{"type":"object","properties":{"category":{"type":"string","enum":["DIAGNOSIS","ALLERGY","OBSERVATION","RECOMMENDATION"]},"label":{"type":"string"},"value":{"type":"string"},"sourceText":{"type":"string"},"confidence":{"type":"number"}},"required":["category","label","value","sourceText","confidence"]}},"unreadableFields":{"type":"array","items":{"type":"string"}}},"required":["documentId","documentType","measuredDate","measurements","keyFacts","unreadableFields"]}}},"required":["documents"]}
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
                                "required": ["scheduledDate", "meals", "exerciseItems"]
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
