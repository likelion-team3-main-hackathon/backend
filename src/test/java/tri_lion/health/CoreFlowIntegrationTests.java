package tri_lion.health;

import com.fasterxml.jackson.databind.*;
import jakarta.servlet.http.Cookie;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import tri_lion.health.service.health.AiJobWorker;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest @AutoConfigureMockMvc @ActiveProfiles("test")
class CoreFlowIntegrationTests {
    @Autowired MockMvc mvc; @Autowired ObjectMapper json; @Autowired AiJobWorker worker;

    @Test void completeCoreFlowAndRotateRefreshToken() throws Exception {
        var login=mvc.perform(post("/api/v1/auth/oauth/google").contentType(MediaType.APPLICATION_JSON)
                .content("{\"idToken\":\"local:google-sub:user@example.com:Test User\"}"))
                .andExpect(status().isCreated()).andReturn();
        JsonNode body=json.readTree(login.getResponse().getContentAsString());
        String access=body.at("/data/accessToken").asText();
        Cookie refresh=login.getResponse().getCookie("refresh_token");
        assertThat(refresh).isNotNull(); assertThat(refresh.isHttpOnly()).isTrue();

        mvc.perform(post("/api/v1/auth/token/refresh").cookie(refresh)).andExpect(status().isOk());
        mvc.perform(post("/api/v1/auth/token/refresh").cookie(refresh)).andExpect(status().isUnauthorized());

        mvc.perform(put("/api/v1/users/me/agreements").header("Authorization","Bearer "+access).contentType(MediaType.APPLICATION_JSON)
                .content("{\"agreements\":[{\"type\":\"TERMS_OF_SERVICE\",\"agreed\":true},{\"type\":\"PRIVACY\",\"agreed\":true},{\"type\":\"SENSITIVE_HEALTH_DATA\",\"agreed\":true}]}"))
                .andExpect(status().isOk());
        mvc.perform(get("/api/v1/health-documents").header("Authorization","Bearer "+access)).andExpect(status().isForbidden());
        mvc.perform(put("/api/v1/users/me/onboarding").header("Authorization","Bearer "+access).contentType(MediaType.APPLICATION_JSON)
                .content("{\"birthDate\":\"1998-03-12\",\"gender\":\"FEMALE\",\"heightCm\":163.5,\"weightKg\":58.2,\"goals\":[\"GENERAL_WELLNESS\"],\"activityLevel\":\"LIGHT\",\"availableExerciseDays\":[\"MONDAY\"],\"availableExerciseMinutes\":40,\"dietaryPreferences\":[],\"allergies\":[],\"dislikedFoods\":[],\"injuries\":[]}"))
                .andExpect(status().isOk());

        byte[] pdf="%PDF-1.4 test".getBytes(StandardCharsets.US_ASCII);
        MockMultipartFile file=new MockMultipartFile("file","health.pdf","application/pdf",pdf);
        var upload=mvc.perform(multipart("/api/v1/health-documents").file(file).param("documentType","INBODY").header("Authorization","Bearer "+access))
                .andExpect(status().isCreated()).andReturn();
        long documentId=json.readTree(upload.getResponse().getContentAsString()).at("/data/documentId").asLong();
        var analysis=mvc.perform(post("/api/v1/health-analyses").header("Authorization","Bearer "+access).header("Idempotency-Key","analysis-1").contentType(MediaType.APPLICATION_JSON).content("{\"documentIds\":["+documentId+"]}"))
                .andExpect(status().isAccepted()).andReturn();
        long analysisId=json.readTree(analysis.getResponse().getContentAsString()).at("/data/analysisId").asLong(); worker.work();
        mvc.perform(get("/api/v1/health-analyses/{id}",analysisId).header("Authorization","Bearer "+access)).andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("COMPLETED"));

        var generation=mvc.perform(post("/api/v1/routines/generations").header("Authorization","Bearer "+access).contentType(MediaType.APPLICATION_JSON)
                .content("{\"analysisId\":"+analysisId+",\"startDate\":\""+java.time.LocalDate.now()+"\",\"durationWeeks\":4,\"mealCountPerDay\":3,\"exerciseDaysPerWeek\":3,\"preferredExerciseTypes\":[\"WALKING\"],\"includeExpertContents\":false}"))
                .andExpect(status().isAccepted()).andReturn();
        long generationId=json.readTree(generation.getResponse().getContentAsString()).at("/data/generationId").asLong(); worker.work();
        var state=mvc.perform(get("/api/v1/routines/generations/{id}",generationId).header("Authorization","Bearer "+access)).andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("COMPLETED")).andReturn();
        long routineId=json.readTree(state.getResponse().getContentAsString()).at("/data/routineId").asLong();
        var detail=mvc.perform(get("/api/v1/routines/{id}",routineId).header("Authorization","Bearer "+access)).andExpect(status().isOk()).andExpect(jsonPath("$.data.days[0].sections[0].exercises.length()").value(11)).andReturn();
        long exerciseId=json.readTree(detail.getResponse().getContentAsString()).at("/data/days/0/sections/0/exercises/0/exerciseId").asLong();
        mvc.perform(patch("/api/v1/routines/{id}/exercises/{exercise}",routineId,exerciseId).header("Authorization","Bearer "+access).contentType(MediaType.APPLICATION_JSON).content("{\"targetValue\":40,\"targetUnit\":\"SECONDS\",\"sets\":2,\"excludeFromAiAdjustment\":true}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.editedBy").value("USER"));
        mvc.perform(post("/api/v1/routine-records").header("Authorization","Bearer "+access).contentType(MediaType.APPLICATION_JSON)
                .content("{\"routineItemId\":"+exerciseId+",\"type\":\"EXERCISE\",\"recordedAt\":\"2026-08-11T10:00:00+09:00\",\"details\":{\"completed\":true},\"condition\":{\"energyLevel\":4,\"painLevel\":1}}"))
                .andExpect(status().isCreated()); worker.work();
        mvc.perform(get("/api/v1/coachings/latest").header("Authorization","Bearer "+access)).andExpect(status().isOk()).andExpect(jsonPath("$.data.disclaimer").exists());
    }
}
