package tri_lion.health.controller.chat;

import static tri_lion.health.dto.chat.ChatDtos.*;

import org.springframework.http.MediaType;
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
            @RequestPart(name = "image", required = false) MultipartFile image) {
        return ApiResponse.success(200, "AI 챗봇 응답 성공", chatService.chat(message, history, image));
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
