package tri_lion.health.dto.chat;

import java.time.Instant;
import java.util.*;

public final class ChatDtos {
    private ChatDtos() {}

    public record ChatHistoryMessage(String role, String text) {}

    public record LookupRequest(String toolName, Map<String, Object> arguments) {
        public LookupRequest {
            arguments = arguments == null ? Map.of() : arguments;
        }
    }

    public record QueryPlan(String resultType, String message, List<LookupRequest> lookups) {
        public QueryPlan {
            lookups = lookups == null ? List.of() : lookups;
        }
    }

    public record LookupResult(String toolName, Map<String, Object> arguments, Object data) {}

    public record AiDecision(
            String resultType,
            String answer,
            String methodName,
            Map<String, Object> arguments,
            String confirmationMessage) {
        public AiDecision {
            methodName = methodName == null ? "" : methodName;
            arguments = arguments == null ? Map.of() : arguments;
            confirmationMessage = confirmationMessage == null ? "" : confirmationMessage;
        }
    }

    public record ChatResponse(
            String responseType,
            String message,
            Long pendingActionId,
            boolean confirmationRequired,
            Long conversationId) {
        public static ChatResponse answer(String type, String message, Long conversationId) {
            return new ChatResponse(type, message, null, false, conversationId);
        }

        public static ChatResponse proposal(String message, Long actionId, Long conversationId) {
            return new ChatResponse("ACTION_PROPOSAL", message, actionId, true, conversationId);
        }
    }

    public record ConversationSummary(
            Long conversationId,
            String title,
            Instant lastMessageAt,
            Instant createdAt,
            Instant updatedAt) {}

    public record MessageResponse(
            Long messageId,
            Long conversationId,
            String role,
            String content,
            String responseType,
            Long pendingActionId,
            boolean hasImage,
            String imageUrl,
            Instant createdAt) {}

    public record ChatImage(byte[] bytes, String contentType) {}

    public record MessagePage(
            Long conversationId,
            String title,
            List<MessageResponse> messages,
            Long nextBeforeMessageId,
            boolean hasMore) {}

    public record ActionResultResponse(
            Long pendingActionId, String status, String message, Object data) {}
}
