package tri_lion.health.controller.record;

import java.util.Map;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import tri_lion.health.common.response.ApiResponse;
import tri_lion.health.service.record.PoseAnalysisService;

@RestController
@RequestMapping("/api/v1/pose-analyses")
public class PoseAnalysisController {
    private final PoseAnalysisService service;

    public PoseAnalysisController(PoseAnalysisService service) {
        this.service = service;
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    ResponseEntity<ApiResponse<Map<String, Object>>> create(
            @RequestPart("image") MultipartFile image,
            @RequestParam(required = false) Long routineItemId,
            @RequestParam String exerciseName) {
        var result = service.create(image, routineItemId, exerciseName);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success(201, "운동 자세 분석 완료", service.view(result)));
    }

    @GetMapping("/{id}")
    ApiResponse<Map<String, Object>> get(@PathVariable Long id) {
        return ApiResponse.success(200, "운동 자세 분석 조회 성공", service.view(service.get(id)));
    }
}
