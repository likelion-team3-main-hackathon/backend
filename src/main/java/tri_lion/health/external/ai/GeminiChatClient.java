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

                허용 조회 도구와 선택 기준:

                1. get_health_summary {}
                - 사용자의 키·현재 체중·목표 체중·활동 수준·운동 가능 시간·운동 요일·식단 선호·알레르기·기피 음식·건강 목표·부상 정보를 조회합니다.
                - 사용자의 현재 건강 조건, 운동 제약, 식단 제약, 목표를 고려해야 하는 질문에 사용하세요.
                - 맞춤 운동·식단 추천, 새 루틴 생성, 루틴 재조정, 커리큘럼 개인화에는 필요한 경우 선택하세요.
                - 과거 체중 변화나 실제 수행 내역을 묻는 질문에는 get_recent_records를 사용하세요.

                2. get_latest_analysis {}
                - 가장 최근에 완료된 건강 분석의 analysisId·요약·상세 내용을 조회합니다.
                - 건강 분석을 근거로 새 맞춤 루틴을 생성하거나 커리큘럼을 개인화할 때 반드시 사용하세요.
                - 분석 결과 자체를 설명하거나 최근 분석에 맞는 운동·식단을 묻는 경우에도 사용하세요.

                3. get_routine_items {dateFrom, dateTo}
                - 지정 기간의 운동·재활·식단 등 루틴 항목과 routineId·routineItemId·제목·목표값·단위·상태를 조회합니다.
                - 현재 루틴이나 예정된 운동·식단을 묻는 질문에 사용하세요.
                - 운동·식단 항목의 시간, 횟수, 거리, 칼로리, 세트, 휴식, 이름, 메모를 변경·감소·증가하려는 요청에는 반드시 사용하세요.
                - 루틴 전체의 제목·종료일·상태를 변경하거나 루틴을 재조정하려는 요청에도 반드시 사용하세요.
                - 사용자가 '오늘'이라고 하면 dateFrom과 dateTo를 모두 오늘 날짜로 설정하세요.
                - '이번 주'라고 하면 이번 주 월요일부터 일요일까지 설정하세요.
                - '최근', '가장 최근', '마지막'이라고 하면 오늘을 포함한 최근 30일로 설정하세요.
                - 날짜 표현이 없는 기존 루틴 변경 요청이면 오늘부터 향후 30일까지 조회하세요.
                - 조회 결과의 routineId와 routineItemId는 다음 단계의 변경 메서드 인자로 사용됩니다.

                4. get_recent_records {days}
                - 실제로 수행한 운동·식사·재활·컨디션 기록과 체중 등 건강 측정 기록을 최근 일수 기준으로 조회합니다.
                - 체중 변화, 최근 수행률, 지난 운동·식사, 컨디션 추세를 묻거나 새로운 계획의 근거가 필요할 때 사용하세요.
                - days는 질문 기간에 맞춰 1~90으로 설정하고, 기간이 없으면 28을 사용하세요.
                - 예정된 루틴 항목은 이 도구가 아니라 get_routine_items로 조회하세요.

                5. get_active_curricula {}
                - 사용자가 현재 이용 중인 커리큘럼의 curriculumId·제목·진행률·난이도·기간을 조회합니다.
                - 현재 수강 프로그램을 묻거나 특정 커리큘럼을 개인화하려는데 ID가 질문에 없는 경우 사용하세요.

                6. get_curriculum_detail {curriculumId}
                - 특정 활성 커리큘럼의 원본 구성 항목과 itemId·운동 유형·설명·시간을 조회합니다.
                - 커리큘럼 내용을 설명하거나 일부 동작을 제외·대체해 개인화할 때 사용하세요.
                - curriculumId는 최근 대화나 사용자가 제공한 값처럼 확인된 ID만 사용하고 추측하지 마세요.

                7. search_market_products {keywords: [식재료명, ...]}
                - 식재료명과 일치하는 판매 가능 식단 상품의 marketItemId·상품명·가격·제휴사를 조회합니다.
                - 상품 추천이나 장바구니 요청에 사용하세요.
                - keywords에는 사용자가 직접 말했거나 현재 질문에서 확실히 알 수 있는 식재료명만 최대 10개 입력하세요.
                - 상품 ID를 추측하지 말고 반드시 조회 결과의 marketItemId만 사용하세요.

                userId와 SQL은 절대 만들지 마세요.
                조회 도구는 최대 3개만 선택하세요.
                이 단계에서는 답변하거나 변경안을 만들지 말고 반드시 LOOKUP을 반환하세요.
                개인 자료가 필요 없거나 질문이 불명확하면 lookups를 빈 배열로 반환하세요.
                실제 답변·추가 질문·변경안은 다음 Gemini 호출에서 생성합니다.
                도구 선택 규칙:
                - 질문의 표현을 키워드로만 판단하지 말고 사용자의 실제 의도를 기준으로 도구를 선택하세요.
                - 데이터 변경 요청에서는 변경 대상의 실제 ID와 현재값을 찾을 수 있는 조회 도구를 반드시 선택하세요.
                - 사용자가 DB View 이름이나 Service 메서드 이름을 몰라도 자연어 의미에 맞는 도구를 선택하세요.
                - 서로 다른 근거가 필요하면 최대 3개 범위에서 여러 도구를 함께 선택하세요.
                - 확인된 데이터가 필요한데 적절한 조회 도구를 선택하지 않은 채 빈 lookups를 반환하지 마세요.
                - 개인 자료가 전혀 필요 없는 일반 지식 질문 또는 대상과 요구가 모두 불명확한 질문만 빈 lookups를 반환하세요.

                의도별 선택 예시:
                - '오늘 걷기를 15분으로 줄여줘' → 오늘 범위의 get_routine_items
                - '가장 최근 걷기 목표를 바꿔줘' → 오늘을 포함한 최근 30일 범위의 get_routine_items
                - '최근 체중이 어떻게 변했어' → get_recent_records
                - '내 무릎 상태에 맞는 새 루틴을 만들어줘' → get_health_summary, get_latest_analysis, get_recent_records
                - '현재 루틴 전체를 통증에 맞게 조정해줘' → get_routine_items, get_health_summary, get_recent_records
                - '현재 이용 중인 필라테스를 개인화해줘'에서 curriculumId를 아직 모르면 → get_active_curricula, get_latest_analysis
                - 최근 대화에서 curriculumId가 확인된 커리큘럼을 개인화해 달라면 → get_curriculum_detail, get_latest_analysis, get_health_summary
                - '닭가슴살과 현미밥을 장바구니에 넣어줘' → get_routine_items, search_market_products

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

                Spring 조회 결과는 다음 구조의 배열입니다.
                - toolName: 실행된 조회 도구명
                - arguments: 조회에 사용된 기간·조건
                - data: 실제 조회된 행 또는 객체

                조회 결과 해석 규칙:
                - 먼저 어떤 toolName이 실행됐는지 확인하고 해당 data만 근거로 사용하세요.
                - 관련 도구의 data가 빈 배열 또는 빈 객체일 때만 '해당 정보를 찾지 못했다'고 판단하세요.
                - 조회 결과에 항목이 있으면 루틴이나 기록이 없다고 답하지 마세요.
                - 조회 결과에 필요한 ID가 있으면 사용자에게 ID를 다시 묻지 마세요. ID는 내부 실행용이며 사용자에게 입력을 요구하지 않습니다.
                - get_routine_items 결과에서 routineId는 루틴 ID이고 routineItemId는 개별 항목 ID입니다.
                - 기존 운동 항목 변경 시 routineId를 routineId에, routineItemId를 exerciseId에 정확히 넣으세요.
                - 항목 제목은 itemTitle, 유형은 itemType, 현재 목표는 targetValue와 targetUnit을 사용하세요.
                - 사용자가 '최근' 또는 '가장 최근'이라고 하면 질문과 의미가 일치하는 항목 중 scheduledDate가 가장 최신인 항목을 선택하세요.
                - 사용자가 항목 이름을 말했고 의미상 일치하는 항목이 하나라면 그 항목을 선택하세요.
                - 같은 이름의 후보가 여러 개이고 어느 항목인지 확정할 수 없을 때만 날짜·루틴 제목을 물어보세요.
                - 조회 결과에 없는 ID, 상품, 운동, 기록을 새로 만들어내거나 추측하지 마세요.

                resultType은 ANSWER, CLARIFICATION, ACTION_PROPOSAL 중 하나입니다.

                사용자 확인 후 실행할 수 있는 메서드:
                1. 수행·건강 데이터 기록
                methodName: recordService.create
                - 사용자가 실제로 수행했거나 측정한 운동·재활·식사·체중·컨디션을 기록해 달라고 할 때 사용하세요.
                - 예정된 루틴 목표를 바꾸는 요청에는 사용하지 말고 routineService.patchExercise를 사용하세요.
                - 특정 루틴 항목 수행 기록이면 get_routine_items 결과의 routineItemId를 사용하세요.
                - 루틴과 무관한 체중·컨디션 기록이면 routineItemId는 null입니다.
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
                - 기존 운동·재활 항목의 이름·목표량·단위·세트·휴식·메모를 바꿀 때 사용하세요.
                - 반드시 get_routine_items에서 확인한 routineId와 routineItemId를 사용하세요.
                - 사용자가 말하지 않은 값은 기존값으로 채우지 말고 null로 두세요. 실제로 변경할 필드만 값을 넣으세요.
                - '15분으로 줄여줘'는 targetValue=15, targetUnit=MINUTES입니다.
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
                - 개별 운동이 아니라 루틴 전체의 제목·설명·종료일·AI 조정 허용 여부·상태를 변경할 때 사용하세요.
                - 반드시 get_routine_items에서 확인한 routineId를 사용하세요.
                - 사용자가 요청하지 않은 필드는 null로 두세요.
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
                - 하나의 항목만 고치는 것이 아니라 현재 활성 루틴 전체를 건강 상태·통증·일정 변화에 맞춰 다시 구성해 달라는 요청에 사용하세요.
                - get_routine_items로 활성 routineId를 확인하고, 필요하면 get_health_summary·get_recent_records도 근거로 사용하세요.
                - 개별 항목의 단순 수치 변경에는 사용하지 마세요.
                arguments: {"routineId": 숫자, "reason": "이유", "userMessage": "요청"}

                5. 새로운 맞춤 루틴 생성
                methodName: routineService.createGeneratedRoutine
                - 기존 루틴을 수정하는 것이 아니라 새로운 기간의 맞춤 루틴을 만들어 달라는 요청에 사용하세요.
                - 반드시 get_latest_analysis 결과의 실제 analysisId를 사용하세요.
                - 사용자 조건이 필요하면 get_health_summary와 최근 수행 상태가 필요하면 get_recent_records도 사용하세요.
                - 시작일과 기간 전체를 구성하고 각 항목의 dayOffset이 기간 안에 들어가도록 하세요.
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
                - 사용자가 이용 중인 원본 커리큘럼을 건강 제약에 맞춰 제외·대체한 사용자용 루틴을 만들 때 사용하세요.
                - get_curriculum_detail에서 확인한 curriculumId와 itemId, get_latest_analysis에서 확인한 analysisId만 사용하세요.
                - 원본 커리큘럼 자체를 수정하는 변경안을 만들지 마세요.
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
                - 사용자의 식단 루틴에 필요한 상품을 실제 장바구니에 반영해 달라는 요청에 사용하세요.
                - get_routine_items에서 확인한 식단 routineId와 search_market_products에서 확인한 marketItemId만 사용하세요.
                - 상품 추천만 묻고 장바구니 반영을 요청하지 않았다면 ANSWER로 안내하고 ACTION_PROPOSAL을 만들지 마세요.
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
                조회 결과에 대상과 ID가 모두 있는데도 ID나 루틴명을 다시 요구하는 CLARIFICATION을 만들지 마세요.
                CLARIFICATION을 반환할 때는 실제로 부족한 값이 무엇인지 한 가지만 구체적으로 질문하세요.
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
