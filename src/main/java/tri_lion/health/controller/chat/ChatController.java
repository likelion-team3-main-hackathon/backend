package tri_lion.health.controller.chat;

import static tri_lion.health.dto.chat.ChatDtos.*;

import java.util.List;
import java.util.Optional;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import tri_lion.health.common.response.ApiResponse;
import tri_lion.health.service.chat.*;

@RestController
@RequestMapping("/api/v1/chat")
public class ChatController {
    private final ChatService chatService;
    private final ChatActionService actionService;

    public ChatController(ChatService chatService, ChatActionService actionService) {
        this.chatService = chatService;
        this.actionService = actionService;
    }

    @PostMapping(value = "/messages", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<ChatResponse> chat(
            @RequestParam(name = "message", required = false, defaultValue = "") String message,
            @RequestParam(name = "history", required = false, defaultValue = "[]") String history,
            @RequestParam(name = "conversationId", required = false) Long conversationId,
            @RequestPart(name = "image", required = false) MultipartFile image) {
        return ApiResponse.success(
                200, "AI 챗봇 응답 성공", chatService.chat(message, history, image, conversationId));
    }

    @PostMapping("/conversations")
    public ApiResponse<ConversationSummary> createConversation() {
        return ApiResponse.success(201, "새 대화방 생성 성공", chatService.createConversation());
    }

    @GetMapping("/conversations")
    public ApiResponse<List<ConversationSummary>> conversations() {
        return ApiResponse.success(200, "대화방 목록 조회 성공", chatService.conversations());
    }

    @GetMapping("/conversations/{conversationId}/messages")
    public ApiResponse<MessagePage> messages(
            @PathVariable Long conversationId,
            @RequestParam(name = "beforeMessageId", required = false) Long beforeMessageId,
            @RequestParam(name = "limit", defaultValue = "50") int limit) {
        return ApiResponse.success(
                200, "대화 내역 조회 성공", chatService.messages(conversationId, beforeMessageId, limit));
    }

    @DeleteMapping("/conversations/{conversationId}")
    public ApiResponse<Void> deleteConversation(@PathVariable Long conversationId) {
        chatService.deleteConversation(conversationId);
        return ApiResponse.success(200, "대화방 삭제 성공", null);
    }

    @GetMapping("/messages/{messageId}/image")
    public ResponseEntity<byte[]> image(@PathVariable Long messageId) {
        ChatImage image = chatService.image(messageId);
        MediaType contentType =
                MediaType.parseMediaType(
                        Optional.ofNullable(image.contentType())
                                .orElse(MediaType.APPLICATION_OCTET_STREAM_VALUE));
        return ResponseEntity.ok()
                .contentType(contentType)
                .cacheControl(CacheControl.noStore())
                .body(image.bytes());
    }

    @PostMapping("/actions/{actionId}/confirm")
    public ApiResponse<ActionResultResponse> confirm(@PathVariable Long actionId) {
        return ApiResponse.success(200, "AI 변경안 실행 성공", actionService.confirm(actionId));
    }

    @PostMapping("/actions/{actionId}/cancel")
    public ApiResponse<ActionResultResponse> cancel(@PathVariable Long actionId) {
        return ApiResponse.success(200, "AI 변경안 취소 성공", actionService.cancel(actionId));
    }
}
