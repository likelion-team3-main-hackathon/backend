package tri_lion.health.service.chat;

import static tri_lion.health.dto.chat.ChatDtos.*;

import com.fasterxml.jackson.databind.*;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;
import tri_lion.health.domain.chat.PendingAiAction;
import tri_lion.health.exception.ApiException;
import tri_lion.health.external.ai.GeminiChatClient;
import tri_lion.health.security.AuthenticatedUser;

@Service
public class ChatService {
    private static final int MAX_MESSAGE_LENGTH = 2000;
    private static final int MAX_HISTORY_MESSAGES = 10;
    private static final long MAX_IMAGE_BYTES = 5L * 1024 * 1024;
    private static final Set<String> IMAGE_TYPES = Set.of("image/jpeg", "image/png", "image/webp");

    private final AuthenticatedUser auth;
    private final GeminiChatClient gemini;
    private final AiReadToolService readTools;
    private final ChatActionService actions;
    private final ChatHistoryService history;
    private final ObjectMapper json;

    public ChatService(
            AuthenticatedUser auth,
            GeminiChatClient gemini,
            AiReadToolService readTools,
            ChatActionService actions,
            ChatHistoryService history,
            ObjectMapper json) {
        this.auth = auth;
        this.gemini = gemini;
        this.readTools = readTools;
        this.actions = actions;
        this.history = history;
        this.json = json;
    }

    public ChatResponse chat(String message, String historyJson, MultipartFile image) {
        return chat(message, historyJson, image, null);
    }

    public ChatResponse chat(
            String message, String historyJson, MultipartFile image, Long requestedConversationId) {
        boolean hasText = StringUtils.hasText(message);
        boolean hasImage = image != null && !image.isEmpty();
        if (!hasText && !hasImage) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "질문이나 사진을 입력해 주세요.");
        }
        if (hasText && message.length() > MAX_MESSAGE_LENGTH) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "질문은 2,000자 이하만 입력할 수 있습니다.");
        }
        if (hasImage) validateImage(image);

        Long userId = auth.sensitive().getId();
        String question = hasText ? message.trim() : "이 사진을 내 상태와 루틴을 고려해 설명해줘.";
        Long conversationId = history.resolve(userId, requestedConversationId);
        String storedHistory = history.aiHistory(userId, conversationId, MAX_HISTORY_MESSAGES);
        String safeHistory =
                "[]".equals(storedHistory) ? validateHistory(historyJson) : storedHistory;
        history.saveUserMessage(userId, conversationId, question, image);

        QueryPlan plan = gemini.plan(question, safeHistory, hasImage);
        validatePlan(plan);

        List<LookupResult> results = readTools.execute(userId, plan.lookups());
        AiDecision decision = gemini.decide(question, safeHistory, results, image);
        validateDecision(decision);

        ChatResponse response;
        if ("ANSWER".equals(decision.resultType())
                || "CLARIFICATION".equals(decision.resultType())) {
            response =
                    ChatResponse.answer(decision.resultType(), decision.answer(), conversationId);
        } else {
            PendingAiAction action = actions.prepare(decision);
            String confirmation =
                    decision.confirmationMessage() == null
                                    || decision.confirmationMessage().isBlank()
                            ? decision.answer()
                            : decision.confirmationMessage();
            response = ChatResponse.proposal(confirmation, action.getId(), conversationId);
        }
        history.saveAssistantMessage(
                userId,
                conversationId,
                response.message(),
                response.responseType(),
                response.pendingActionId());
        return response;
    }

    public ConversationSummary createConversation() {
        return history.create(auth.sensitive().getId());
    }

    public List<ConversationSummary> conversations() {
        return history.list(auth.sensitive().getId());
    }

    public MessagePage messages(Long conversationId, Long beforeMessageId, int limit) {
        return history.messages(auth.sensitive().getId(), conversationId, beforeMessageId, limit);
    }

    public void deleteConversation(Long conversationId) {
        history.delete(auth.sensitive().getId(), conversationId);
    }

    public ChatImage image(Long messageId) {
        return history.image(auth.sensitive().getId(), messageId);
    }

    private void validatePlan(QueryPlan plan) {
        if (plan == null || plan.resultType() == null) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, "AI 조회 계획이 비어 있습니다.");
        }
        if (!"LOOKUP".equals(plan.resultType())) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, "AI 조회 계획 형식이 올바르지 않습니다.");
        }
    }

    private void validateDecision(AiDecision decision) {
        if (decision == null
                || decision.resultType() == null
                || decision.answer() == null
                || decision.answer().isBlank()) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, "AI 최종 응답이 올바르지 않습니다.");
        }
        if (!Set.of("ANSWER", "CLARIFICATION", "ACTION_PROPOSAL").contains(decision.resultType())) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, "AI 최종 응답 유형이 올바르지 않습니다.");
        }
        boolean hasOperations = decision.operations() != null && !decision.operations().isEmpty();
        if ("ACTION_PROPOSAL".equals(decision.resultType()) != hasOperations) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, "AI 변경안 형식이 올바르지 않습니다.");
        }
        if (hasOperations && decision.operations().size() > 50) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, "AI 변경 작업은 한 번에 최대 50개까지 가능합니다.");
        }
        if (hasOperations
                && decision.operations().stream()
                        .anyMatch(
                                operation ->
                                        operation == null
                                                || operation.methodName() == null
                                                || operation.methodName().isBlank())) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, "AI 변경 작업의 메서드가 비어 있습니다.");
        }
        if (!"ACTION_PROPOSAL".equals(decision.resultType())
                && claimsDatabaseChangeCompleted(decision.answer())) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, "AI가 실행하지 않은 데이터 변경을 완료했다고 응답했습니다.");
        }
    }

    private boolean claimsDatabaseChangeCompleted(String message) {
        if (message == null) return false;
        return List.of(
                        "저장 완료", "저장했습니다", "기록 완료", "기록했습니다", "변경 완료", "변경했습니다", "생성 완료", "생성했습니다",
                        "추가 완료", "추가했습니다", "반영 완료", "반영했습니다")
                .stream()
                .anyMatch(message::contains);
    }

    private String validateHistory(String historyJson) {
        if (historyJson == null || historyJson.isBlank()) return "[]";
        if (historyJson.length() > 25000) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "대화 기록이 너무 깁니다.");
        }
        try {
            JavaType type =
                    json.getTypeFactory()
                            .constructCollectionType(List.class, ChatHistoryMessage.class);
            List<ChatHistoryMessage> history = json.readValue(historyJson, type);
            List<ChatHistoryMessage> filtered =
                    history.stream()
                            .filter(Objects::nonNull)
                            .filter(item -> Set.of("user", "model").contains(item.role()))
                            .filter(item -> item.text() != null && !item.text().isBlank())
                            .filter(item -> item.text().length() <= 2000)
                            .toList();
            List<ChatHistoryMessage> valid =
                    filtered.stream()
                            .skip(Math.max(0, filtered.size() - MAX_HISTORY_MESSAGES))
                            .toList();
            return json.writeValueAsString(valid);
        } catch (Exception exception) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "대화 기록 형식이 올바르지 않습니다.");
        }
    }

    private void validateImage(MultipartFile image) {
        if (image.getSize() > MAX_IMAGE_BYTES) {
            throw new ApiException(HttpStatus.PAYLOAD_TOO_LARGE, "사진은 5MB 이하만 사용할 수 있습니다.");
        }
        if (!IMAGE_TYPES.contains(image.getContentType())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "JPG, PNG, WEBP 사진만 사용할 수 있습니다.");
        }
    }
}
