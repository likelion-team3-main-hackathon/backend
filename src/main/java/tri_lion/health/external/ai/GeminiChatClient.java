package tri_lion.health.external.ai;

import static tri_lion.health.dto.chat.ChatDtos.*;

import com.fasterxml.jackson.databind.*;
import java.time.*;
import java.util.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.multipart.MultipartFile;
import tri_lion.health.exception.ApiException;

@Component
public class GeminiChatClient {
    private static final Logger log = LoggerFactory.getLogger(GeminiChatClient.class);
    private final RestClient restClient;
    private final ObjectMapper json;
    private final String apiKey;
    private final String model;

    public GeminiChatClient(
            RestClient.Builder builder,
            ObjectMapper json,
            @Value("${app.gemini.api-key:}") String apiKey,
            @Value("${app.gemini.model:gemini-3.1-flash-lite}") String model) {
        this.restClient = builder.build();
        this.json = json;
        this.apiKey = apiKey;
        this.model = model;
    }

    public QueryPlan plan(String message, String historyJson, boolean hasImage) {
        String prompt =
                """
                당신은 MCC 웰니스 챗봇의 조회 계획 담당자입니다.
                오늘 날짜는 %s입니다.

                허용 조회:
                - get_health_summary {}
                - get_latest_analysis {}
                - get_routine_items {dateFrom, dateTo}
                - get_recent_records {days}
                - get_active_curricula {}
                - get_curriculum_detail {curriculumId}
                - search_market_products {keywords: [식재료명, ...]}

                userId와 SQL은 절대 만들지 마세요.
                조회 도구는 최대 3개만 선택하세요.
                이 단계에서는 답변하거나 변경안을 만들지 말고 반드시 LOOKUP을 반환하세요.
                개인 자료가 필요 없거나 질문이 불명확하면 lookups를 빈 배열로 반환하세요.
                실제 답변·추가 질문·변경안은 다음 Gemini 호출에서 생성합니다.
                커리큘럼 개인화에는 get_active_curricula와 get_curriculum_detail을 사용하세요.
                상품 장바구니 요청에는 get_routine_items와 search_market_products를 사용하세요.
                식단에 필요한 재료명은 keywords 배열에 최대 10개까지 한 번에 넣으세요.

                반드시 다음 JSON 형식만 반환하세요.
                {
                  "resultType": "LOOKUP",
                  "message": "",
                  "lookups": [
                    {"toolName": "허용된 도구명", "arguments": {}}
                  ]
                }

                사진 첨부 여부: %s
                최근 대화: %s
                사용자 질문: %s
                """
                        .formatted(
                                LocalDate.now(ZoneId.of("Asia/Seoul")),
                                hasImage,
                                historyJson,
                                message);
        return requestJson(prompt, null, QueryPlan.class);
    }

    public AiDecision decide(
            String message,
            String historyJson,
            List<LookupResult> lookupResults,
            MultipartFile image) {
        String data;
        try {
            data = json.writeValueAsString(lookupResults);
        } catch (Exception exception) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "AI 조회 결과를 변환하지 못했습니다.");
        }

        String prompt =
                """
                당신은 MCC 웰니스 챗봇의 최종 판단 담당자입니다.
                제공된 조회 결과의 값과 ID만 사용하세요. ID를 추측하지 마세요.

                resultType은 ANSWER, CLARIFICATION, ACTION_PROPOSAL 중 하나입니다.

                사용자 확인 후 실행할 수 있는 메서드:
                1. 수행·건강 데이터 기록
                methodName: recordService.create
                arguments:
                {
                  "routineItemId": 숫자 또는 null,
                  "type": "EXERCISE|REHABILITATION|MEAL|WEIGHT|CONDITION|OTHER",
                  "details": {},
                  "condition": {
                    "energyLevel": 1~5 또는 null,
                    "painLevel": 0~5 또는 null,
                    "memo": "문자열 또는 null"
                  }
                }
                체중 기록의 details는 반드시 {"weightKg": 70.5} 형식입니다.
                식사 기록의 details에는 foods와 calories를 넣으세요.

                2. 기존 운동 항목 변경
                methodName: routineService.patchExercise
                arguments:
                {
                  "routineId": 조회 결과의 ID,
                  "exerciseId": 조회 결과의 routineItemId,
                  "name": "변경값 또는 null",
                  "targetValue": 숫자 또는 null,
                  "targetUnit": "허용 단위 또는 null",
                  "sets": 숫자 또는 null,
                  "restSeconds": 숫자 또는 null,
                  "memo": "문자열 또는 null",
                  "excludeFromAiAdjustment": true|false|null
                }

                3. 루틴 기본정보 변경
                methodName: routineService.patch
                arguments:
                {
                  "routineId": 조회 결과의 ID,
                  "title": "변경값 또는 null",
                  "description": "변경값 또는 null",
                  "endDate": "YYYY-MM-DD 또는 null",
                  "aiAdjustmentAllowed": true|false|null,
                  "status": "DRAFT|ACTIVE|COMPLETED|PAUSED 또는 null"
                }

                4. 기존 루틴 전체 재조정
                methodName: routineService.adjust
                arguments: {"routineId": 숫자, "reason": "이유", "userMessage": "요청"}

                5. 새로운 맞춤 루틴 생성
                methodName: routineService.createGeneratedRoutine
                arguments:
                {
                  "analysisId": 완료된 건강 분석 ID,
                  "title": "루틴 제목",
                  "goal": "목표",
                  "startDate": "YYYY-MM-DD",
                  "durationWeeks": 1~12,
                  "items": [
                    {
                      "dayOffset": 시작일 기준 0 이상의 일수,
                      "sectionType": "구간 유형",
                      "sectionTitle": "구간 제목",
                      "itemType": "EXERCISE|REHABILITATION|MEAL|WEIGHT|CONDITION|OTHER",
                      "title": "항목 제목",
                      "content": "설명 또는 식단 JSON 문자열",
                      "scheduledTime": "HH:mm",
                      "targetValue": 0보다 큰 숫자,
                      "targetUnit": "허용 단위",
                      "sets": 1 이상의 숫자 또는 null,
                      "restSeconds": 0 이상의 숫자 또는 null,
                      "memo": "주의사항 또는 null"
                    }
                  ]
                }
                사용자가 요구한 기간 전체를 구성하되 항목은 최대 200개입니다.

                6. 커리큘럼 개인화
                methodName: routineService.personalizeCurriculum
                arguments:
                {
                  "curriculumId": 조회한 원본 커리큘럼 ID,
                  "analysisId": 완료된 건강 분석 ID,
                  "startDate": "YYYY-MM-DD",
                  "durationWeeks": 1~12,
                  "excludedItemIds": [제외할 원본 itemId],
                  "replacementItems": [
                    {
                      "sourceItemId": 대체할 원본 itemId,
                      "activityType": "EXERCISE|REHABILITATION|MEAL|WEIGHT|CONDITION|OTHER",
                      "title": "대체 항목 제목",
                      "description": "대체 이유와 설명",
                      "durationMinutes": 1~300,
                      "details": {}
                    }
                  ]
                }
                원본 커리큘럼은 수정하지 않고 사용자용 루틴을 새로 만드세요.

                7. 식단 상품 장바구니 생성
                methodName: expansionService.createMealCart
                arguments:
                {
                  "routineId": 조회한 식단 루틴 ID,
                  "partner": "제휴사 이름",
                  "items": [
                    {"marketItemId": 조회한 상품 ID, "quantity": 1~20}
                  ]
                }

                운동 단위:
                - SECONDS
                - MINUTES
                - REPETITIONS
                - METERS
                - KILOMETERS
                - KCAL

                변경을 이미 완료했다고 표현하지 마세요.
                변경 요청에는 사용자가 확인할 구체적인 문장을 만드세요.
                사용자가 기록·저장·변경·생성·개인화·장바구니 반영을 명확히 요청했고
                필요한 값이 모두 있으면 반드시 ACTION_PROPOSAL을 반환하세요.
                필요한 값이나 조회 결과가 부족하면 CLARIFICATION을 반환하세요.
                ANSWER에서는 저장 완료·변경 완료·생성 완료·장바구니 추가 완료라고 말하지 마세요.
                의료 진단·처방·결제·환불·전문가 승인은 실행하지 마세요.
                사진만으로 확정할 수 없는 건강 상태는 단정하지 마세요.

                반드시 다음 JSON 형식만 반환하세요.
                {
                  "resultType": "ANSWER|CLARIFICATION|ACTION_PROPOSAL",
                  "answer": "사용자에게 보여줄 답변",
                  "methodName": "허용된 메서드 또는 빈 문자열",
                  "arguments": {},
                  "confirmationMessage": "확인 문장 또는 빈 문자열"
                }

                최근 대화: %s
                사용자 질문: %s
                Spring 조회 결과: %s
                """
                        .formatted(historyJson, message, data);
        return requestJson(prompt, image, AiDecision.class);
    }

    private <T> T requestJson(String prompt, MultipartFile image, Class<T> responseType) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "Gemini API 키가 설정되지 않았습니다.");
        }

        List<Map<String, Object>> parts = new ArrayList<>();
        parts.add(Map.of("text", prompt));
        if (image != null && !image.isEmpty()) {
            try {
                parts.add(
                        Map.of(
                                "inline_data",
                                Map.of(
                                        "mime_type",
                                        image.getContentType(),
                                        "data",
                                        Base64.getEncoder().encodeToString(image.getBytes()))));
            } catch (Exception exception) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "사진 파일을 읽을 수 없습니다.");
            }
        }

        Map<String, Object> body =
                Map.of(
                        "contents", List.of(Map.of("role", "user", "parts", parts)),
                        "generationConfig", Map.of("responseMimeType", "application/json"));

        String raw;
        try {
            raw =
                    restClient
                            .post()
                            .uri(
                                    builder ->
                                            builder.scheme("https")
                                                    .host("generativelanguage.googleapis.com")
                                                    .path("/v1beta/models/{model}:generateContent")
                                                    .queryParam("key", apiKey)
                                                    .build(model))
                            .contentType(MediaType.APPLICATION_JSON)
                            .body(body)
                            .retrieve()
                            .body(String.class);
        } catch (RestClientResponseException exception) {
            log.error(
                    "Gemini HTTP failure. model={}, status={}, response={}",
                    model,
                    exception.getStatusCode().value(),
                    exception.getResponseBodyAsString(),
                    exception);
            throw new ApiException(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "Gemini API request failed (HTTP " + exception.getStatusCode().value() + ").");
        } catch (Exception exception) {
            log.error("Gemini transport failure. model={}", model, exception);
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "Gemini API connection failed.");
        }

        try {
            JsonNode root = json.readTree(raw);
            JsonNode partsNode = root.path("candidates").path(0).path("content").path("parts");
            if (!partsNode.isArray() || partsNode.isEmpty()) {
                throw new ApiException(HttpStatus.BAD_GATEWAY, "Gemini returned no text parts.");
            }

            Exception lastMappingFailure = null;
            int textPartCount = 0;
            for (JsonNode part : partsNode) {
                JsonNode textNode = part.path("text");
                if (!textNode.isTextual() || textNode.asText().isBlank()) continue;
                textPartCount++;
                try {
                    return json.readValue(stripCodeFence(textNode.asText()), responseType);
                } catch (Exception mappingFailure) {
                    lastMappingFailure = mappingFailure;
                }
            }

            log.error(
                    "Gemini JSON mapping failure. model={}, responseType={}, textParts={}, cause={}",
                    model,
                    responseType.getSimpleName(),
                    textPartCount,
                    lastMappingFailure == null
                            ? "no textual response"
                            : lastMappingFailure.getMessage());
            throw new ApiException(
                    HttpStatus.BAD_GATEWAY,
                    "Gemini response JSON did not match the expected format.");
        } catch (ApiException exception) {
            throw exception;
        } catch (Exception exception) {
            log.error(
                    "Gemini response envelope parsing failed. model={}, responseType={}",
                    model,
                    responseType.getSimpleName(),
                    exception);
            throw new ApiException(HttpStatus.BAD_GATEWAY, "Gemini response could not be parsed.");
        }
    }

    private String stripCodeFence(String value) {
        String result = value.trim();
        if (result.startsWith("```")) {
            result = result.replaceFirst("^```(?:json)?\\s*", "");
            result = result.replaceFirst("\\s*```$", "");
        }
        return result.trim();
    }
}
