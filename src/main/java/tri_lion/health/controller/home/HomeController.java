package tri_lion.health.controller.home;

import java.util.*;
import org.springframework.web.bind.annotation.*;
import tri_lion.health.common.response.ApiResponse;
import tri_lion.health.security.AuthenticatedUser;

@RestController
@RequestMapping("/api/v1/home")
public class HomeController {
    private final AuthenticatedUser auth;

    public HomeController(AuthenticatedUser a) {
        auth = a;
    }

    @GetMapping
    ApiResponse<Object> home() {
        var u = auth.active();
        return ApiResponse.success(
                200,
                "홈 정보 조회 성공",
                Map.of(
                        "user",
                        Map.of("name", Optional.ofNullable(u.getName()).orElse(u.getNickname())),
                        "activeCurriculums",
                        List.of(),
                        "alerts",
                        List.of()));
    }
}
