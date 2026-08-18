package tri_lion.health.external.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
public class MealPhotoAiClient {
    private final RestClient client;
    private final ObjectMapper json;
    private final String provider;
    private final String apiKey;
    private final String model;
    private final PromptCatalog.VersionedPrompt prompt;

    public MealPhotoAiClient(
            RestClient.Builder builder,
            ObjectMapper json,
            PromptCatalog prompts,
            @Value("${app.ai.provider:fake}") String provider,
            @Value("${app.ai.gemini.api-key:}") String apiKey,
            @Value("${app.ai.gemini.base-url}") String baseUrl,
            @Value("${app.ai.models.meal-photo:gemini-3.5-flash-lite}") String model) {
        this.client = builder.baseUrl(baseUrl).build();
        this.json = json;
        this.provider = provider;
        this.apiKey = apiKey;
        this.model = model;
        this.prompt = prompts.mealPhoto();
    }

    public Result analyze(byte[] image, String contentType) {
        if (!"gemini".equalsIgnoreCase(provider)) {
            return new Result(List.of(new Food("사진 속 식사", 300, 520, 58, 32, 18)), 0.5, model);
        }
        if (apiKey.isBlank()) {
            throw new IllegalStateException("식단 사진 분석에는 GOOGLE_API_KEY가 필요합니다.");
        }
        try {
            Map<String, Object> schema = json.readValue(
                    """
                    {"type":"object","properties":{"foods":{"type":"array","items":{"type":"object","properties":{"name":{"type":"string"},"servingGrams":{"type":"number"},"calories":{"type":"number"},"carbohydrateGrams":{"type":"number"},"proteinGrams":{"type":"number"},"fatGrams":{"type":"number"}},"required":["name","servingGrams","calories","carbohydrateGrams","proteinGrams","fatGrams"]}},"confidence":{"type":"number"}},"required":["foods","confidence"]}
                    """,
                    Map.class);
            Map<String, Object> body = Map.of(
                    "contents",
                    List.of(Map.of(
                            "parts",
                            List.of(
                                    Map.of("text", prompt.content()),
                                    Map.of(
                                            "inlineData",
                                            Map.of(
                                                    "mimeType", contentType,
                                                    "data", Base64.getEncoder().encodeToString(image)))))),
                    "generationConfig",
                    Map.of("responseMimeType", "application/json", "responseJsonSchema", schema));
            JsonNode response = client.post()
                    .uri(uri -> uri.path("/models/{model}:generateContent")
                            .queryParam("key", apiKey)
                            .build(model))
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(JsonNode.class);
            String text = response.path("candidates")
                    .path(0)
                    .path("content")
                    .path("parts")
                    .path(0)
                    .path("text")
                    .asText();
            JsonNode result = json.readTree(text);
            List<Food> foods = new ArrayList<>();
            for (JsonNode food : result.path("foods")) {
                foods.add(new Food(
                        food.path("name").asText(),
                        positive(food, "servingGrams"),
                        positive(food, "calories"),
                        positive(food, "carbohydrateGrams"),
                        positive(food, "proteinGrams"),
                        positive(food, "fatGrams")));
            }
            if (foods.isEmpty()) {
                throw new IllegalArgumentException("사진에서 음식을 찾지 못했습니다.");
            }
            return new Result(
                    foods,
                    Math.max(0, Math.min(1, result.path("confidence").asDouble())),
                    model);
        } catch (RuntimeException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IllegalStateException("식단 사진 분석 결과를 읽지 못했습니다.", exception);
        }
    }

    private double positive(JsonNode node, String field) {
        return Math.max(0, node.path(field).asDouble());
    }

    public record Food(
            String name,
            double servingGrams,
            double calories,
            double carbohydrateGrams,
            double proteinGrams,
            double fatGrams) {}

    public record Result(List<Food> foods, double confidence, String modelVersion) {}
}
