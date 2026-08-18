package tri_lion.health.external.ai;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Component;

@Component
public class PromptCatalog {
    private static final Pattern SAFE_VERSION = Pattern.compile("[a-z0-9][a-z0-9._-]{0,79}");

    private final VersionedPrompt documentExtraction;
    private final VersionedPrompt healthAnalysis;
    private final VersionedPrompt routineGeneration;
    private final VersionedPrompt recordCoaching;
    private final VersionedPrompt mealPhoto;
    private final VersionedPrompt poseAnalysis;

    public PromptCatalog(
            ResourceLoader resources,
            @Value("${app.ai.prompts.document-extraction-version:document-v3-iso-measured-date}")
                    String documentExtractionVersion,
            @Value("${app.ai.prompts.health-analysis-version:health-v3-multi-document}")
                    String healthAnalysisVersion,
            @Value("${app.ai.prompts.routine-generation-version:routine-v7-explicit-date-range}")
                    String routineGenerationVersion,
            @Value("${app.ai.prompts.record-coaching-version:coaching-v2}")
                    String recordCoachingVersion,
            @Value("${app.ai.prompts.meal-photo-version:meal-photo-v1}") String mealPhotoVersion,
            @Value("${app.ai.prompts.pose-analysis-version:pose-analysis-v2}")
                    String poseAnalysisVersion) {
        documentExtraction = load(resources, "document-extraction", documentExtractionVersion);
        healthAnalysis = load(resources, "health-analysis", healthAnalysisVersion);
        routineGeneration = load(resources, "routine-generation", routineGenerationVersion);
        recordCoaching = load(resources, "record-coaching", recordCoachingVersion);
        mealPhoto = load(resources, "meal-photo", mealPhotoVersion);
        poseAnalysis = load(resources, "pose-analysis", poseAnalysisVersion);
    }

    public VersionedPrompt documentExtraction() {
        return documentExtraction;
    }

    public VersionedPrompt healthAnalysis() {
        return healthAnalysis;
    }

    public VersionedPrompt routineGeneration() {
        return routineGeneration;
    }

    public VersionedPrompt recordCoaching() {
        return recordCoaching;
    }

    public VersionedPrompt mealPhoto() {
        return mealPhoto;
    }

    public VersionedPrompt poseAnalysis() {
        return poseAnalysis;
    }

    private VersionedPrompt load(ResourceLoader resources, String type, String version) {
        if (version == null || !SAFE_VERSION.matcher(version).matches())
            throw new IllegalStateException("AI 프롬프트 버전 형식이 올바르지 않습니다: " + type);
        String path = "classpath:prompts/" + type + "/" + version + ".md";
        Resource resource = resources.getResource(path);
        try {
            String content = resource.getContentAsString(StandardCharsets.UTF_8).trim();
            if (content.isBlank()) throw new IllegalStateException("AI 프롬프트가 비어 있습니다: " + path);
            return new VersionedPrompt(version, content, sha256(content));
        } catch (IllegalStateException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IllegalStateException("AI 프롬프트를 읽을 수 없습니다: " + path, exception);
        }
    }

    private String sha256(String content) throws Exception {
        return HexFormat.of()
                .formatHex(
                        MessageDigest.getInstance("SHA-256")
                                .digest(content.getBytes(StandardCharsets.UTF_8)));
    }

    public record VersionedPrompt(String version, String content, String sha256) {
        public String storedVersion() {
            return version + "@" + sha256.substring(0, 12);
        }
    }
}
