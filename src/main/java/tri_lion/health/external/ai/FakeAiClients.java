package tri_lion.health.external.ai;

import org.springframework.context.annotation.*;

@Configuration
@Profile({"local", "test"})
public class FakeAiClients {
    @Bean
    AiClients.OcrClient ocr() {
        return f -> "deterministic local OCR text";
    }

    @Bean
    AiClients.LlmClient llm() {
        return new AiClients.LlmClient() {
            public String healthAnalysis(String i) {
                return "{\"goals\":[{\"type\":\"GENERAL_WELLNESS\",\"description\":\"꾸준한 활동 습관 형성\"}],\"precautions\":[],\"nutritionConstraints\":[],\"exerciseConstraints\":[],\"disclaimer\":\"본 분석은 의료 진단을 대체하지 않습니다.\"}";
            }

            public String coaching(String i) {
                return "기록을 잘 남겼어요. 무리하지 말고 현재 강도를 유지하세요.";
            }
        };
    }
}
