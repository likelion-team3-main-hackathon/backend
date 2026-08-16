package tri_lion.health;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
import static tri_lion.health.dto.chat.ChatDtos.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Test;
import tri_lion.health.domain.user.User;
import tri_lion.health.external.ai.GeminiChatClient;
import tri_lion.health.security.AuthenticatedUser;
import tri_lion.health.service.chat.*;

class ChatServiceFlowTests {
    @Test
    void alwaysPerformsPlanningLookupAndFinalDecisionEvenWithoutPersonalLookup() {
        AuthenticatedUser auth = mock(AuthenticatedUser.class);
        User user = mock(User.class);
        GeminiChatClient gemini = mock(GeminiChatClient.class);
        AiReadToolService readTools = mock(AiReadToolService.class);
        ChatActionService actions = mock(ChatActionService.class);
        ChatHistoryService history = mock(ChatHistoryService.class);
        ChatService service =
                new ChatService(auth, gemini, readTools, actions, history, new ObjectMapper());

        when(auth.sensitive()).thenReturn(user);
        when(user.getId()).thenReturn(7L);
        when(history.resolve(7L, null)).thenReturn(55L);
        when(history.aiHistory(7L, 55L, 10)).thenReturn("[]");
        when(gemini.plan("안녕", "[]", false)).thenReturn(new QueryPlan("LOOKUP", "", List.of()));
        when(readTools.execute(7L, List.of())).thenReturn(List.of());
        when(gemini.decide("안녕", "[]", List.of(), null))
                .thenReturn(new AiDecision("ANSWER", "안녕하세요.", "", null, ""));

        ChatResponse response = service.chat("안녕", "[]", null);

        assertThat(response.responseType()).isEqualTo("ANSWER");
        assertThat(response.message()).isEqualTo("안녕하세요.");
        assertThat(response.conversationId()).isEqualTo(55L);
        verify(gemini).plan("안녕", "[]", false);
        verify(readTools).execute(7L, List.of());
        verify(gemini).decide("안녕", "[]", List.of(), null);
        verify(history).saveUserMessage(7L, 55L, "안녕", null);
        verify(history).saveAssistantMessage(7L, 55L, "안녕하세요.", "ANSWER", null);
        verifyNoInteractions(actions);
    }
}
