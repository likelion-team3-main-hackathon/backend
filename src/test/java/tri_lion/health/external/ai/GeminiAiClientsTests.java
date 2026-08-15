package tri_lion.health.external.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class GeminiAiClientsTests {
    @Test
    void allStructuredOutputSchemasAreValidJson() {
        GeminiAiClients.validateSchemas();
    }

    @Test
    void sendsDocumentAsInlineDataAndReadsStructuredResponse() {
        RestClient.Builder builder =
                RestClient.builder()
                        .baseUrl("https://gemini.test/v1beta")
                        .defaultHeader("x-goog-api-key", "test-key");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        GeminiAiClients.GeminiGateway gateway =
                new GeminiAiClients.GeminiGateway(builder.build(), new ObjectMapper());
        server.expect(requestTo("https://gemini.test/v1beta/models/test-model:generateContent"))
                .andExpect(method(org.springframework.http.HttpMethod.POST))
                .andExpect(header("x-goog-api-key", "test-key"))
                .andExpect(
                        jsonPath("$.contents[0].parts[0].text")
                                .value(
                                        "sourceDocumentId=7, declaredDocumentType=OTHER, measuredAt=unknown"))
                .andExpect(
                        jsonPath("$.contents[0].parts[1].inlineData.mimeType")
                                .value("application/pdf"))
                .andExpect(jsonPath("$.contents[0].parts[1].inlineData.data").value("JVBERi0xLjQ="))
                .andExpect(
                        jsonPath("$.generationConfig.responseMimeType").value("application/json"))
                .andRespond(
                        withSuccess(
                                "{\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"{\\\"ok\\\":true}\"}]}}]}",
                                MediaType.APPLICATION_JSON));

        String result =
                gateway.generate(
                        "test-model",
                        "extract",
                        Map.of("type", "object"),
                        List.of(
                                new AiClients.DocumentInput(
                                        7L,
                                        "application/pdf",
                                        "%PDF-1.4".getBytes(StandardCharsets.US_ASCII))));

        assertThat(result).isEqualTo("{\"ok\":true}");
        server.verify();
    }
}
