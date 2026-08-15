package tri_lion.health.external.ai;

import java.time.LocalDate;
import java.util.List;

public final class AiClients {
    private AiClients() {}

    public interface OcrClient {
        String extract(List<DocumentInput> documents);

        default String modelVersion() {
            return "unknown";
        }
    }

    public interface LlmClient {
        String healthAnalysis(String input);

        String routineGeneration(String input);

        CoachingResult coaching(String input);

        default boolean live() {
            return false;
        }

        default String analysisModelVersion() {
            return "unknown";
        }

        default String routineModelVersion() {
            return "unknown";
        }

        default String coachingModelVersion() {
            return "unknown";
        }
    }

    public record DocumentInput(
            Long documentId,
            String documentType,
            LocalDate measuredAt,
            String contentType,
            byte[] bytes) {
        public DocumentInput(Long documentId, String contentType, byte[] bytes) {
            this(documentId, null, null, contentType, bytes);
        }
    }

    public record CoachingResult(String message, String safetyLevel) {}
}
