package tri_lion.health;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.*;
import jakarta.servlet.http.Cookie;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import tri_lion.health.service.health.AiJobWorker;

@SpringBootTest(
        properties = {"spring.jpa.hibernate.ddl-auto=validate", "spring.flyway.enabled=true"})
@AutoConfigureMockMvc
@ActiveProfiles("test")
class CoreFlowIntegrationTests {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired AiJobWorker worker;
    @Autowired JdbcTemplate db;

    @Test
    void openApiExposesBearerAuthenticationScheme() throws Exception {
        mvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.info.title").value("Tri Lion Wellness API"))
                .andExpect(jsonPath("$.components.securitySchemes.bearerAuth.type").value("http"))
                .andExpect(
                        jsonPath("$.components.securitySchemes.bearerAuth.scheme").value("bearer"));
    }

    @Test
    void completeCoreFlowAndRotateRefreshToken() throws Exception {
        var login =
                mvc.perform(
                                post("/api/v1/auth/oauth/google")
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(
                                                "{\"idToken\":\"local:google-sub:user@example.com:Test User\"}"))
                        .andExpect(status().isCreated())
                        .andReturn();
        JsonNode body = json.readTree(login.getResponse().getContentAsString());
        String access = body.at("/data/accessToken").asText();
        long userId = body.at("/data/userId").asLong();
        Cookie refresh = login.getResponse().getCookie("refresh_token");
        assertThat(refresh).isNotNull();
        assertThat(refresh.isHttpOnly()).isTrue();

        mvc.perform(post("/api/v1/auth/token/refresh").cookie(refresh)).andExpect(status().isOk());
        mvc.perform(post("/api/v1/auth/token/refresh").cookie(refresh))
                .andExpect(status().isUnauthorized());

        mvc.perform(
                        put("/api/v1/users/me/agreements")
                                .header("Authorization", "Bearer " + access)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        "{\"agreements\":[{\"type\":\"TERMS_OF_SERVICE\",\"agreed\":true},{\"type\":\"PRIVACY\",\"agreed\":true},{\"type\":\"SENSITIVE_HEALTH_DATA\",\"agreed\":true}]}"))
                .andExpect(status().isOk());
        mvc.perform(get("/api/v1/health-documents").header("Authorization", "Bearer " + access))
                .andExpect(status().isForbidden());
        mvc.perform(
                        put("/api/v1/users/me/onboarding")
                                .header("Authorization", "Bearer " + access)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        "{\"birthDate\":\"1998-03-12\",\"gender\":\"FEMALE\",\"heightCm\":163.5,\"weightKg\":58.2,\"goals\":[\"GENERAL_WELLNESS\"],\"activityLevel\":\"LIGHT\",\"availableExerciseDays\":[\"MONDAY\"],\"availableExerciseMinutes\":40,\"dietaryPreferences\":[],\"allergies\":[],\"dislikedFoods\":[],\"injuries\":[]}"))
                .andExpect(status().isOk());

        byte[] pdf = "%PDF-1.4 test".getBytes(StandardCharsets.US_ASCII);
        MockMultipartFile file =
                new MockMultipartFile("file", "health.pdf", "application/pdf", pdf);
        var upload =
                mvc.perform(
                                multipart("/api/v1/health-documents")
                                        .file(file)
                                        .param("documentType", "INBODY")
                                        .header("Authorization", "Bearer " + access))
                        .andExpect(status().isCreated())
                        .andReturn();
        long documentId =
                json.readTree(upload.getResponse().getContentAsString())
                        .at("/data/documentId")
                        .asLong();
        var analysis =
                mvc.perform(
                                post("/api/v1/health-analyses")
                                        .header("Authorization", "Bearer " + access)
                                        .header("Idempotency-Key", "analysis-1")
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content("{\"documentIds\":[" + documentId + "]}"))
                        .andExpect(status().isAccepted())
                        .andReturn();
        long analysisId =
                json.readTree(analysis.getResponse().getContentAsString())
                        .at("/data/analysisId")
                        .asLong();
        mvc.perform(
                        post("/api/v1/health-analyses")
                                .header("Authorization", "Bearer " + access)
                                .header("Idempotency-Key", "analysis-1")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"documentIds\":[" + documentId + "]}"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.data.analysisId").value(analysisId));
        assertThat(
                        db.queryForObject(
                                "select count(*) from ai_jobs where user_id=? and job_type='HEALTH_ANALYSIS' and idempotency_key='analysis-1'",
                                Integer.class,
                                userId))
                .isEqualTo(1);
        worker.work();
        mvc.perform(
                        get("/api/v1/health-analyses/{id}", analysisId)
                                .header("Authorization", "Bearer " + access))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("COMPLETED"));

        var generation =
                mvc.perform(
                                post("/api/v1/routines/generations")
                                        .header("Authorization", "Bearer " + access)
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(
                                                "{\"analysisId\":"
                                                        + analysisId
                                                        + ",\"startDate\":\""
                                                        + java.time.LocalDate.now()
                                                        + "\",\"durationWeeks\":3,\"mealCountPerDay\":3,\"exerciseDaysPerWeek\":3,\"preferredExerciseTypes\":[\"WALKING\"],\"includeExpertContents\":false,\"selectedRecommendationIds\":[\"MEAL_PRIMARY\",\"EXERCISE_PRIMARY\"]}"))
                        .andExpect(status().isAccepted())
                        .andReturn();
        long generationId =
                json.readTree(generation.getResponse().getContentAsString())
                        .at("/data/generationId")
                        .asLong();
        worker.work();
        var state =
                mvc.perform(
                                get("/api/v1/routines/generations/{id}", generationId)
                                        .header("Authorization", "Bearer " + access))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.data.status").value("COMPLETED"))
                        .andReturn();
        long routineId =
                json.readTree(state.getResponse().getContentAsString())
                        .at("/data/routineId")
                        .asLong();
        var detail =
                mvc.perform(
                                get("/api/v1/routines/{id}", routineId)
                                        .header("Authorization", "Bearer " + access))
                        .andExpect(status().isOk())
                        .andReturn();
        JsonNode routineDetail =
                json.readTree(detail.getResponse().getContentAsString()).at("/data");
        assertThat(routineDetail.path("days").size()).isEqualTo(21);
        assertThat(routineDetail.path("days").get(0).path("mealSummaryTitle").asText())
                .isNotBlank();
        assertThat(routineDetail.path("days").get(0).path("exerciseSummaryTitle").asText())
                .isNotBlank();
        JsonNode firstExercise = null;
        JsonNode firstMeal = null;
        var exerciseIds = new ArrayList<Long>();
        for (JsonNode day : routineDetail.path("days")) {
            for (JsonNode section : day.path("sections")) {
                for (JsonNode item : section.path("exercises")) {
                    if ("MEAL".equals(item.path("activityType").asText()) && firstMeal == null)
                        firstMeal = item;
                    if ("EXERCISE".equals(item.path("activityType").asText())
                            && firstExercise == null) firstExercise = item;
                    if ("EXERCISE".equals(item.path("activityType").asText())
                            && exerciseIds.size() < 3)
                        exerciseIds.add(item.path("exerciseId").asLong());
                }
            }
        }
        assertThat(firstMeal).isNotNull();
        assertThat(firstExercise).isNotNull();
        long exerciseId = firstExercise.path("exerciseId").asLong();
        mvc.perform(
                        patch("/api/v1/routines/{id}/exercises/{exercise}", routineId, exerciseId)
                                .header("Authorization", "Bearer " + access)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        "{\"targetValue\":40,\"targetUnit\":\"SECONDS\",\"sets\":2,\"excludeFromAiAdjustment\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.editedBy").value("USER"));
        mvc.perform(
                        post("/api/v1/routine-records")
                                .header("Authorization", "Bearer " + access)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        "{\"routineItemId\":"
                                                + exerciseId
                                                + ",\"type\":\"EXERCISE\",\"recordedAt\":\"2026-08-11T10:00:00+09:00\",\"details\":{\"completed\":true},\"condition\":{\"energyLevel\":4,\"painLevel\":1}}"))
                .andExpect(status().isCreated());
        long mealId = firstMeal.path("exerciseId").asLong();
        mvc.perform(
                        post("/api/v1/routine-records")
                                .header("Authorization", "Bearer " + access)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        "{\"routineItemId\":"
                                                + mealId
                                                + ",\"type\":\"MEAL\",\"recordedAt\":\"2026-08-11T09:00:00+09:00\",\"details\":{\"completed\":false,\"skipped\":true}}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.recordStatus").value("SKIPPED"));
        mvc.perform(
                        post("/api/v1/routine-records")
                                .header("Authorization", "Bearer " + access)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        "{\"routineItemId\":"
                                                + mealId
                                                + ",\"type\":\"MEAL\",\"recordedAt\":\"2026-08-11T12:00:00+09:00\",\"details\":{\"completed\":true,\"calories\":510}}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.recordStatus").value("COMPLETED"));
        mvc.perform(
                        post("/api/v1/routine-records/batch")
                                .header("Authorization", "Bearer " + access)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        "{\"records\":[{\"routineItemId\":"
                                                + exerciseIds.get(1)
                                                + ",\"type\":\"EXERCISE\",\"recordedAt\":\"2026-08-11T10:10:00+09:00\",\"details\":{\"completed\":true,\"exerciseCount\":1}},{\"routineItemId\":"
                                                + exerciseIds.get(2)
                                                + ",\"type\":\"EXERCISE\",\"recordedAt\":\"2026-08-11T10:10:00+09:00\",\"details\":{\"completed\":true,\"exerciseCount\":1}}]}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.count").value(2));
        MockMultipartFile activityImage =
                new MockMultipartFile(
                        "image",
                        "exercise.jpg",
                        "image/jpeg",
                        new byte[] {(byte) 0xff, (byte) 0xd8, (byte) 0xff, (byte) 0xe0});
        mvc.perform(
                        multipart("/api/v1/routine-records/images")
                                .file(activityImage)
                                .header("Authorization", "Bearer " + access))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.imageKey").exists());
        worker.work();
        mvc.perform(get("/api/v1/coachings/latest").header("Authorization", "Bearer " + access))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.disclaimer").exists());

        mvc.perform(
                        post("/api/v1/routine-records")
                                .header("Authorization", "Bearer " + access)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        "{\"type\":\"REHABILITATION\",\"recordedAt\":\"2026-08-11T11:00:00+09:00\",\"details\":{\"program\":\"무릎 가동 범위 훈련\",\"durationMinutes\":20}}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.activityType").value("REHABILITATION"));
        mvc.perform(
                        post("/api/v1/routine-records")
                                .header("Authorization", "Bearer " + access)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        "{\"type\":\"MEAL\",\"recordedAt\":\"2026-08-11T12:00:00+09:00\",\"details\":{\"mealType\":\"LUNCH\",\"menu\":[\"현미밥\",\"닭가슴살\"]}}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.activityType").value("MEAL"));
        mvc.perform(
                        get("/api/v1/routine-records?date=2026-08-11&type=REHABILITATION")
                                .header("Authorization", "Bearer " + access))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].type").value("REHABILITATION"));
        mvc.perform(
                        post("/api/v1/routine-records")
                                .header("Authorization", "Bearer " + access)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        "{\"type\":\"YOGA\",\"recordedAt\":\"2026-08-11T13:00:00+09:00\",\"details\":{}}"))
                .andExpect(status().isBadRequest());

        db.update(
                "insert into experts(user_id,specialty,qualification_info,verification_status,applied_at) values(?,?,?,?,CURRENT_TIMESTAMP)",
                userId,
                "운동,재활,영양",
                "test-only",
                "APPROVED");
        mvc.perform(
                        post("/api/v1/expert-contents")
                                .header("Authorization", "Bearer " + access)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        "{\"title\":\"운동과 식단 통합 프로그램\",\"description\":\"혼합 커리큘럼\",\"category\":\"WELLNESS\",\"difficulty\":\"BEGINNER\",\"contentType\":\"MIXED\",\"durationWeeks\":4,\"sessionsPerWeek\":5,\"price\":39000,\"targetGoals\":[\"GENERAL_WELLNESS\"],\"contraindications\":[],\"items\":[{\"week\":1,\"order\":1,\"activityType\":\"EXERCISE\",\"title\":\"저강도 걷기\",\"durationMinutes\":20,\"details\":{\"intensity\":\"LIGHT\"}},{\"week\":1,\"order\":2,\"activityType\":\"MEAL\",\"title\":\"고단백 점심\",\"scheduledTime\":\"12:00:00\",\"details\":{\"proteinGrams\":30}}]}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.contentType").value("MIXED"))
                .andExpect(jsonPath("$.data.activityTypes.length()").value(2));
        mvc.perform(
                        post("/api/v1/expert-contents")
                                .header("Authorization", "Bearer " + access)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        "{\"title\":\"잘못된 단일 타입\",\"description\":\"타입 불일치\",\"category\":\"FITNESS\",\"difficulty\":\"BEGINNER\",\"contentType\":\"EXERCISE\",\"durationWeeks\":1,\"sessionsPerWeek\":1,\"price\":0,\"items\":[{\"week\":1,\"order\":1,\"activityType\":\"MEAL\",\"title\":\"잘못된 항목\"}]}"))
                .andExpect(status().isBadRequest());
    }
}
