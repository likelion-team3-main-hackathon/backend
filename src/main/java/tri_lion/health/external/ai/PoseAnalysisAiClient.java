package tri_lion.health.external.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
public class PoseAnalysisAiClient {
    private final RestClient client;
    private final ObjectMapper json;
    private final PromptCatalog.VersionedPrompt prompt;
    private final String provider;
    private final String apiKey;
    private final String model;

    public PoseAnalysisAiClient(
            RestClient.Builder builder,
            ObjectMapper json,
            PromptCatalog prompts,
            @Value("${app.ai.provider:fake}") String provider,
            @Value("${app.ai.gemini.api-key:}") String apiKey,
            @Value("${app.ai.gemini.base-url}") String baseUrl,
            @Value("${app.ai.models.pose-analysis:gemini-3.5-flash-lite}") String model) {
        this.client = builder.baseUrl(baseUrl).build();
        this.json = json;
        this.prompt = prompts.poseAnalysis();
        this.provider = provider;
        this.apiKey = apiKey;
        this.model = model;
    }

    public Result analyze(byte[] image, String contentType, String exerciseName) {
        if (!"gemini".equalsIgnoreCase(provider)) {
            return new Result(
                    82,
                    List.of("무릎이 발끝보다 안쪽으로 모여 있어요."),
                    List.of("무릎을 두 번째 발가락 방향으로 밀어주세요."),
                    0.78,
                    "",
                    model,
                    prompt.storedVersion());
        }
        if (apiKey.isBlank()) throw new IllegalStateException("자세 분석에는 GOOGLE_API_KEY가 필요합니다.");
        try {
            Map<String, Object> schema =
                    json.readValue(
                            """
                    {"type":"object","properties":{"poseScore":{"type":"integer"},"detectedIssues":{"type":"array","items":{"type":"string"}},"feedback":{"type":"array","items":{"type":"string"}},"confidence":{"type":"number"},"safetyWarning":{"type":"string"}},"required":["poseScore","detectedIssues","feedback","confidence","safetyWarning"]}
                    """,
                            Map.class);
            String instruction = prompt.content() + "\n\n분석할 운동명: " + exerciseName;
            Map<String, Object> body =
                    Map.of(
                            "contents",
                                    List.of(
                                            Map.of(
                                                    "parts",
                                                    List.of(
                                                            Map.of("text", instruction),
                                                            Map.of(
                                                                    "inlineData",
                                                                    Map.of(
                                                                            "mimeType",
                                                                            contentType,
                                                                            "data",
                                                                            Base64.getEncoder()
                                                                                    .encodeToString(
                                                                                            image)))))),
                            "generationConfig",
                                    Map.of(
                                            "responseMimeType",
                                            "application/json",
                                            "responseJsonSchema",
                                            schema));
            JsonNode response =
                    client.post()
                            .uri(
                                    uri ->
                                            uri.path("/models/{model}:generateContent")
                                                    .queryParam("key", apiKey)
                                                    .build(model))
                            .contentType(MediaType.APPLICATION_JSON)
                            .body(body)
                            .retrieve()
                            .body(JsonNode.class);
            JsonNode result =
                    json.readTree(
                            response.path("candidates")
                                    .path(0)
                                    .path("content")
                                    .path("parts")
                                    .path(0)
                                    .path("text")
                                    .asText());
            return new Result(
                    Math.max(0, Math.min(100, result.path("poseScore").asInt())),
                    strings(result.path("detectedIssues")),
                    strings(result.path("feedback")),
                    Math.max(0, Math.min(1, result.path("confidence").asDouble())),
                    result.path("safetyWarning").asText(""),
                    model,
                    prompt.storedVersion());
        } catch (RuntimeException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IllegalStateException("운동 자세 분석 결과를 읽지 못했습니다.", exception);
        }
    }

    private List<String> strings(JsonNode values) {
        List<String> result = new ArrayList<>();
        values.forEach(
                value -> {
                    if (!value.asText().isBlank()) result.add(value.asText());
                });
        return result;
    }

    public record Result(
            int poseScore,
            List<String> detectedIssues,
            List<String> feedback,
            double confidence,
            String safetyWarning,
            String modelVersion,
            String promptVersion) {}
}
