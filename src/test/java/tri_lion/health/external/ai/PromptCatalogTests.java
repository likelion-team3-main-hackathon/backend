package tri_lion.health.external.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.DefaultResourceLoader;

class PromptCatalogTests {
    @Test
    void loadsVersionedPromptsAndCreatesContentFingerprint() {
        PromptCatalog prompts = catalog("routine-v7-explicit-date-range");

        assertThat(prompts.documentExtraction().content()).contains("sourceDocumentId");
        assertThat(prompts.documentExtraction().content()).contains("YYYY-MM-DD", "인바디 점수");
        assertThat(prompts.healthAnalysis().content()).contains("routineRecommendations");
        assertThat(prompts.routineGeneration().content())
                .contains(
                        "expectedEndDate",
                        "days.length == totalDays",
                        "mealSummaryTitle",
                        "exerciseSummaryTitle");
        assertThat(prompts.routineGeneration().content()).doesNotContain("2026-08-15");
        assertThat(prompts.recordCoaching().content()).contains("의료 진단");
        assertThat(prompts.routineGeneration().storedVersion())
                .matches("routine-v7-explicit-date-range@[0-9a-f]{12}");
    }

    @Test
    void failsFastWhenConfiguredPromptVersionDoesNotExist() {
        assertThatThrownBy(() -> catalog("missing-version"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("프롬프트를 읽을 수 없습니다");
    }

    @Test
    void rejectsUnsafePromptVersionPath() {
        assertThatThrownBy(() -> catalog("../secret"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("버전 형식");
    }

    private PromptCatalog catalog(String routineVersion) {
        return new PromptCatalog(
                new DefaultResourceLoader(),
                "document-v3-iso-measured-date",
                "health-v3-multi-document",
                routineVersion,
                "coaching-v2");
    }
}
