package tri_lion.health.controller.record;

import jakarta.validation.Valid;
import java.time.*;
import java.util.Map;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import tri_lion.health.common.response.ApiResponse;
import tri_lion.health.dto.request.record.MealAnalysisUpdateRequest;
import tri_lion.health.service.record.MealAnalysisService;

@RestController
@RequestMapping("/api/v1")
public class MealAnalysisController {
    private final MealAnalysisService service;

    public MealAnalysisController(MealAnalysisService service) {
        this.service = service;
    }

    @PostMapping(value = "/meal-analyses", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    ResponseEntity<ApiResponse<Map<String, Object>>> create(
            @RequestPart("image") MultipartFile image,
            @RequestParam(required = false) Long routineItemId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
                    OffsetDateTime recordedAt) {
        var result = service.create(image, routineItemId, recordedAt);
        return ResponseEntity.status(201)
                .body(ApiResponse.success(201, "식단 사진 분석 완료", service.view(result)));
    }

    @GetMapping("/meal-analyses/{id}")
    ApiResponse<Map<String, Object>> get(@PathVariable Long id) {
        return ApiResponse.success(200, "식단 사진 분석 조회 성공", service.view(service.get(id)));
    }

    @PatchMapping("/meal-analyses/{id}")
    ApiResponse<Map<String, Object>> update(
            @PathVariable Long id, @Valid @RequestBody MealAnalysisUpdateRequest request) {
        return ApiResponse.success(200, "식단 분석 수정 성공", service.view(service.update(id, request)));
    }

    @PostMapping("/meal-analyses/{id}/confirm")
    ResponseEntity<ApiResponse<Map<String, Object>>> confirm(@PathVariable Long id) {
        var result = service.confirm(id);
        return ResponseEntity.status(201)
                .body(ApiResponse.success(201, "실제 식단 기록 확정 성공", service.view(result)));
    }

    @GetMapping("/nutrition-reports/daily")
    ApiResponse<Map<String, Object>> daily(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return ApiResponse.success(200, "일일 영양 비교 조회 성공", service.daily(date));
    }
}
